package ai.firmus.interop.fhir

import com.mongodb.ErrorCategory
import com.mongodb.MongoException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteConcernException
import com.mongodb.MongoWriteException
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant
import java.util.Date

/**
 * Writes projections into the `patients` and `encounters` collections.
 *
 * ## Write concern
 *
 * `MAJORITY`, explicitly. The Go gateway reads with majority read concern, and the pair is what
 * makes the read side's guarantee real: a write acknowledged by a primary that then loses an
 * election can be rolled back, and a clinician who saw an admission in the console a moment ago
 * finds it gone. Neither half is useful without the other, so neither is left at its default.
 *
 * ## No multi-document transaction
 *
 * A patient and an encounter are two documents in two collections written from one event, and it
 * is tempting to wrap them in a session transaction. It is not done, for two reasons. First, it
 * would buy less than it appears to: the Kafka offset commit happens outside any Mongo
 * transaction, so the pipeline is still at-least-once end to end and still has to converge under
 * replay — which the deterministic ids and the staleness guard already make it do. Second, the
 * write order is chosen so that the intermediate state is one that occurs naturally anyway. The
 * patient is written first because the gateway's `Encounters` call looks the patient up before
 * listing visits and returns not-found if it is missing; an encounter without its patient is
 * invisible and reads as data loss, whereas a patient with no encounters yet is simply a patient
 * who has just been registered.
 */
class MongoWriter(
    database: MongoDatabase,
    private val metrics: Metrics,
    private val log: Logger,
) : ProjectionStore {

    private val patients: MongoCollection<Document> =
        database.getCollection("patients").withWriteConcern(WriteConcern.MAJORITY)

    private val encounters: MongoCollection<Document> =
        database.getCollection("encounters").withWriteConcern(WriteConcern.MAJORITY)

    /**
     * Creates the indexes the write path depends on.
     *
     * The gateway creates the same indexes at its own startup and `createIndex` is idempotent, so
     * this is not a duplicate by accident. The unique index on `medicalRecordNumber` is a
     * correctness constraint on *writes* — it is what makes "two documents claiming the same
     * patient" impossible rather than merely unlikely — and the writer must not depend on the
     * reader having been deployed first for that invariant to hold.
     */
    fun ensureIndexes() {
        patients.createIndex(
            Indexes.ascending("medicalRecordNumber"),
            IndexOptions().unique(true).name("uniq_mrn"),
        )
        patients.createIndex(Indexes.ascending("searchTerms"), IndexOptions().name("search_terms"))
        patients.createIndex(
            Indexes.ascending("foldedFamilyName", "foldedGivenName", "medicalRecordNumber"),
            IndexOptions().name("name_order"),
        )
        encounters.createIndex(
            Indexes.compoundIndex(Indexes.ascending("medicalRecordNumber"), Indexes.descending("admittedAt")),
            IndexOptions().name("encounters_by_patient"),
        )
        log.info("mongo.indexes.ensured")
    }

    override fun write(admission: MappedAdmission): WriteOutcome {
        val patientWritten = upsertIfNewer(patients, admission.patient.toDocument(), admission.patient.lastUpdated)
        if (patientWritten) metrics.recordPatientUpserted() else metrics.recordStaleSkipped()

        val encounter = admission.encounter
            ?: return WriteOutcome(
                patientWritten = patientWritten,
                encounterWritten = false,
                patientStale = !patientWritten,
                encounterStale = false,
            )

        val encounterWritten = upsertIfNewer(encounters, encounter.toDocument(), encounter.lastUpdated)
        if (encounterWritten) metrics.recordEncounterUpserted() else metrics.recordStaleSkipped()

        return WriteOutcome(
            patientWritten = patientWritten,
            encounterWritten = encounterWritten,
            patientStale = !patientWritten,
            encounterStale = !encounterWritten,
        )
    }

    /**
     * Upserts [document] unless what is stored is strictly newer than [recordedAt].
     *
     * @return true when the document was written, false when the guard rejected it as stale
     */
    private fun upsertIfNewer(
        collection: MongoCollection<Document>,
        document: Document,
        recordedAt: Instant,
    ): Boolean {
        val id = document.getString("_id")

        // `_id` is excluded from the $set payload and carried only by the filter. MongoDB rejects
        // any update that touches `_id`, even one that sets it to the value it already has; on an
        // upsert-insert the server takes it from the filter's equality clause instead.
        val payload = Document(document).also { it.remove("_id") }

        return try {
            val result = collection.updateOne(
                stalenessGuardFilter(id, recordedAt),
                Document("\$set", payload),
                UpdateOptions().upsert(true),
            )
            result.matchedCount > 0 || result.upsertedId != null
        } catch (e: MongoWriteException) {
            // The load-bearing catch.
            //
            // An upsert whose filter matches nothing inserts a document built from the filter's
            // top-level equality conditions. When the guard rejects the write, the `_id` clause
            // still matches — the document exists — but the `lastUpdated` clause does not, so the
            // server sees "no match" and attempts an insert with that same `_id`, which collides
            // with the document already there and raises E11000.
            //
            // A duplicate key on this exact write therefore means precisely one thing: stored data
            // is newer than the event in hand. That is the guard working, not a failure, so it is
            // swallowed here rather than retried or dead-lettered. Anything else is rethrown.
            if (ErrorCategory.fromErrorCode(e.error.code) == ErrorCategory.DUPLICATE_KEY) {
                log.debug(
                    "projection.write.stale",
                    "collection" to collection.namespace.collectionName,
                    "resourceId" to id,
                )
                false
            } else {
                throw translate(e)
            }
        } catch (e: MongoException) {
            throw translate(e)
        }
    }

    /**
     * Classifies a driver exception into retry-or-not.
     *
     * `MongoWriteConcernException` is treated as retryable, which is worth being explicit about:
     * it means the write reached a primary but the requested number of replicas did not
     * acknowledge in time, so it may or may not have been applied and may or may not survive a
     * failover. Retrying an ambiguous write is normally how you get a duplicate — it is safe here
     * only because the write is an upsert onto a deterministic `_id`. That is the practical payoff
     * of [ResourceIds], and the reason the ambiguity can be resolved by simply trying again.
     *
     * A `MongoWriteException` that is not a duplicate key is the opposite case: the server
     * examined the write and refused it — schema validation, a document over 16MB, an illegal
     * field name. Nothing about that changes on a retry, so it is permanent and belongs in the
     * dead-letter topic where a human can see it.
     */
    private fun translate(e: MongoException): ProjectionWriteException {
        val retryable = when (e) {
            is MongoWriteConcernException -> true
            is MongoWriteException -> false
            is MongoTimeoutException, is MongoSocketException -> true
            is MongoNotPrimaryException, is MongoNodeIsRecoveringException -> true
            // Covers the driver's own retryable-write classification for anything not enumerated
            // above, including the transient errors a sharded cluster raises during failover.
            else -> e.hasErrorLabel(RETRYABLE_WRITE_ERROR_LABEL) ||
                e.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)
        }
        // The driver's message is not interpolated into ours: it can quote the document that
        // failed. Logger.safeMessage suppresses it too, but building it into a message that is
        // "ours" would defeat that check.
        return ProjectionWriteException(
            "mongo write failed (${e.javaClass.simpleName})",
            retryable = retryable,
            cause = e,
        )
    }

    companion object {
        // Spelled as literals rather than pulled from the driver's constants so the classification
        // does not silently change meaning across a driver upgrade.
        private const val RETRYABLE_WRITE_ERROR_LABEL = "RetryableWriteError"
        private const val TRANSIENT_TRANSACTION_ERROR_LABEL = "TransientTransactionError"

        /**
         * The filter that encodes [isStale] as a server-side predicate.
         *
         * There are necessarily two expressions of one rule — a Kotlin predicate for reasoning and
         * testing, and a BSON filter for the server, which cannot run Kotlin. The filter is built
         * by this function alone and `StalenessTest` asserts its exact shape, so a change to the
         * semantics has to be made in both places or a test fails.
         *
         * `$not: {$gt: ...}` rather than `$lte`, because the two differ on a document that has no
         * `lastUpdated` at all: a comparison against a missing field is false, so `$lte` would
         * reject every write to such a document forever, while `$not` matches it. That covers a
         * row written before this field existed — and it keeps the predicate on one field, so the
         * `_id` equality stays the only top-level equality clause and the server has exactly one
         * unambiguous way to build the document it inserts on an upsert.
         */
        fun stalenessGuardFilter(id: String, recordedAt: Instant): Bson = Filters.and(
            Filters.eq("_id", id),
            Filters.not(Filters.gt("lastUpdated", Date.from(recordedAt))),
        )
    }
}

/**
 * Opens the client and hands back the database.
 *
 * Split out from [MongoWriter] so the writer takes a `MongoDatabase` and never owns a connection
 * pool — which is what lets it be constructed in a test against a driver-level fake, and what
 * keeps connection lifecycle in [main] where the shutdown sequence lives.
 */
fun mongoDatabase(client: MongoClient, name: String): MongoDatabase = client.getDatabase(name)
