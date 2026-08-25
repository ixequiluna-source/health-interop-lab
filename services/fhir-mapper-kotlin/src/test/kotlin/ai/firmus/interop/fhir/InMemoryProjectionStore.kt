package ai.firmus.interop.fhir

import org.bson.Document
import java.time.Instant

/**
 * A [ProjectionStore] that enforces the same two rules the Mongo one does — upsert by
 * deterministic `_id`, reject strictly older writes — and can be told to fail.
 *
 * It is honest about what it proves. It shares [isStale] with nothing else; the Mongo writer
 * cannot use a Kotlin predicate because the comparison happens on the server. What this store
 * exercises is the behaviour *above* storage: that the processor retries a transient failure and
 * propagates a permanent one, that ordering across a sequence of events produces the right final
 * state, and that a replay is a no-op. The server-side half of the rule is pinned separately by
 * `MongoWriterFilterTest`, which asserts the exact BSON of the guard filter.
 */
class InMemoryProjectionStore : ProjectionStore {

    val patients = mutableMapOf<String, Document>()
    val encounters = mutableMapOf<String, Document>()

    var writes: Int = 0
        private set

    /** Failures to raise, consumed one per [write] call. A null entry means "succeed". */
    private val failures = ArrayDeque<ProjectionWriteException?>()

    fun failNext(vararg errors: ProjectionWriteException?) {
        failures.addAll(errors.toList())
    }

    override fun write(admission: MappedAdmission): WriteOutcome {
        writes++
        failures.removeFirstOrNull()?.let { throw it }

        val patientWritten = upsert(patients, admission.patient.toDocument(), admission.patient.lastUpdated)
        val encounter = admission.encounter
            ?: return WriteOutcome(patientWritten, false, !patientWritten, false)

        val encounterWritten = upsert(encounters, encounter.toDocument(), encounter.lastUpdated)
        return WriteOutcome(patientWritten, encounterWritten, !patientWritten, !encounterWritten)
    }

    private fun upsert(
        collection: MutableMap<String, Document>,
        document: Document,
        recordedAt: Instant,
    ): Boolean {
        val id = document.getString("_id")
        val storedLastUpdated = (collection[id]?.get("lastUpdated") as? java.util.Date)?.toInstant()
        if (isStale(storedLastUpdated, recordedAt)) return false
        collection[id] = document
        return true
    }
}
