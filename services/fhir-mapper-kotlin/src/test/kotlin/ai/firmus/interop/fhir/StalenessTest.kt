package ai.firmus.interop.fhir

import org.bson.BsonDocument
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalenessTest {

    private val mapper = Fixtures.mapper()
    private val earlier = Instant.parse("2026-08-25T09:00:00Z")
    private val later = Instant.parse("2026-08-25T17:00:00Z")

    // --- the rule ----------------------------------------------------------------------------

    @Test
    fun `an event older than what is stored is stale`() {
        assertTrue(isStale(storedLastUpdated = later, incomingRecordedAt = earlier))
    }

    @Test
    fun `an event newer than what is stored is not stale`() {
        assertFalse(isStale(storedLastUpdated = earlier, incomingRecordedAt = later))
    }

    /**
     * A tie means two events carry the same second and were produced in quick succession for the
     * same patient. On a topic partitioned by MRN they were delivered in order, so arrival order is
     * authoritative and the later arrival must win. Rejecting ties would silently drop the second
     * of two corrections made in the same second.
     */
    @Test
    fun `a tie is not stale, so the later arrival wins`() {
        assertFalse(isStale(storedLastUpdated = later, incomingRecordedAt = later))
    }

    @Test
    fun `nothing stored is never stale`() {
        assertFalse(isStale(storedLastUpdated = null, incomingRecordedAt = earlier))
    }

    // --- the rule as the server sees it ------------------------------------------------------

    /**
     * The guard exists twice — as [isStale] for reasoning, and as a BSON filter for the server,
     * which cannot run Kotlin. This pins the filter's exact shape so the two cannot drift apart
     * silently.
     */
    @Test
    fun `the guard filter is an id match anded with a not-newer check`() {
        val filter = flattenAnd(MongoWriter.stalenessGuardFilter("abc123", later).toBsonDocument())

        // The `_id` equality has to stay the only top-level equality clause: it is what the server
        // uses to build the document it would insert, and that collision is how a stale write is
        // detected at all.
        assertEquals("abc123", filter.getString("_id").value)

        // `$not: {$gt: ...}` rather than `$lte`. The two differ on a document that has no
        // lastUpdated at all — a comparison against a missing field is false, so `$lte` would
        // reject every write to such a row forever, while `$not` matches it. And `$not $gt` admits
        // a tie, which is deliberate: see the tie case above.
        val guard = filter.getDocument("lastUpdated").getDocument("\$not")
        assertEquals(later.toEpochMilli(), guard.getDateTime("\$gt").value)
    }

    @Test
    fun `the guard compares against a BSON date, matching what the projection writes`() {
        // Both sides are milliseconds since the epoch. If the projection wrote a value with
        // sub-millisecond precision, an exact replay would compare as strictly newer than itself
        // and the guard would mean something other than what it says.
        val filter = flattenAnd(MongoWriter.stalenessGuardFilter("abc123", later).toBsonDocument())
        val guardMillis = filter.getDocument("lastUpdated").getDocument("\$not").getDateTime("\$gt").value

        val written = mapper.map(Fixtures.event(recordedAt = "2026-08-25T17:00:00Z"))
            .patient.toDocument()["lastUpdated"] as Date

        assertEquals(written.time, guardMillis)
    }

    /**
     * The driver renders `Filters.and` of clauses with distinct keys as a single flat document
     * rather than an explicit `$and` array — the two are equivalent to the server. Normalising
     * here keeps the assertions about semantics instead of about the driver's rendering choice.
     */
    private fun flattenAnd(document: BsonDocument): BsonDocument {
        if (!document.containsKey("\$and")) return document
        val flat = BsonDocument()
        document.getArray("\$and").forEach { flat.putAll(it.asDocument()) }
        return flat
    }

    // --- end-to-end ordering behaviour --------------------------------------------------------

    @Test
    fun `a replayed older event does not overwrite newer data`() {
        val store = InMemoryProjectionStore()
        val processor = processor(store)

        // The A08 correction lands first (or the A01 is replayed afterwards, which is the same
        // thing from the store's point of view).
        processor.process(payload(recordedAt = "2026-08-25T17:00:00Z", familyName = "Núñez Luna"))
        processor.process(payload(recordedAt = "2026-08-25T09:00:00Z", familyName = "Nunez"))

        val stored = store.patients.values.single()
        assertEquals("Núñez Luna", stored.getString("familyName"))
        assertEquals(Date.from(later), stored["lastUpdated"])
    }

    @Test
    fun `a newer event does overwrite`() {
        val store = InMemoryProjectionStore()
        val processor = processor(store)

        processor.process(payload(recordedAt = "2026-08-25T09:00:00Z", familyName = "Nunez"))
        processor.process(payload(recordedAt = "2026-08-25T17:00:00Z", familyName = "Núñez Luna"))

        assertEquals("Núñez Luna", store.patients.values.single().getString("familyName"))
    }

    @Test
    fun `the stale outcome is reported rather than swallowed`() {
        val store = InMemoryProjectionStore()
        val processor = processor(store)

        processor.process(payload(recordedAt = "2026-08-25T17:00:00Z"))
        val result = processor.process(payload(recordedAt = "2026-08-25T09:00:00Z"))

        val applied = result as ProcessingResult.Applied
        assertFalse(applied.outcome.patientWritten)
        assertTrue(applied.outcome.patientStale)
        assertTrue(applied.outcome.encounterStale)
    }

    /**
     * Idempotency and ordering are separate mechanisms. This is the idempotency half: the same
     * event twice, with nothing older involved, must leave one document, not two — which is what
     * the deterministic `_id` buys and why at-least-once delivery is survivable.
     */
    @Test
    fun `delivering the same event twice leaves exactly one of each document`() {
        val store = InMemoryProjectionStore()
        val processor = processor(store)

        processor.process(Fixtures.FULL_ADMIT)
        processor.process(Fixtures.FULL_ADMIT)

        assertEquals(1, store.patients.size)
        assertEquals(1, store.encounters.size)
        assertEquals(2, store.writes)
    }

    private fun processor(store: InMemoryProjectionStore) = AdmissionProcessor(
        mapper = mapper,
        store = store,
        metrics = Metrics(),
        log = Fixtures.silentLogger(),
        retry = RetryPolicy(attempts = 1, baseBackoffMillis = 0, maxBackoffMillis = 0),
        sleeper = { },
    )

    private fun payload(recordedAt: String, familyName: String = "Núñez") = """
        {"schemaVersion":"1.0.0","eventId":"evt-${recordedAt.hashCode()}",
        "messageType":"ADT^A08","sendingFacility":"HGS","recordedAt":"$recordedAt",
        "patient":{"medicalRecordNumber":"MRN-88421","familyName":"$familyName","givenName":"Ixequi"},
        "encounter":{"visitNumber":"V-0099","patientClass":"I"}}
    """
}
