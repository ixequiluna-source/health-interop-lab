package ai.firmus.interop.fhir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdmissionProcessorTest {

    private val store = InMemoryProjectionStore()
    private val metrics = Metrics()
    private val slept = mutableListOf<Long>()

    private fun processor(retry: RetryPolicy = RetryPolicy(3, 100, 400)) = AdmissionProcessor(
        mapper = Fixtures.mapper(),
        store = store,
        metrics = metrics,
        log = Fixtures.silentLogger(),
        retry = retry,
        sleeper = { slept.add(it) },
    )

    // --- the happy path -----------------------------------------------------------------------

    @Test
    fun `maps and writes a well-formed admission`() {
        val result = processor().process(Fixtures.FULL_ADMIT)

        val applied = assertIs<ProcessingResult.Applied>(result)
        assertEquals("6f1c0f2e-2c3a-4f6b-9a1d-0d1f2a3b4c5d", applied.eventId)
        assertTrue(applied.outcome.patientWritten)
        assertTrue(applied.outcome.encounterWritten)
        assertEquals(1, store.patients.size)
        assertEquals(1, store.encounters.size)
    }

    @Test
    fun `writes a patient and no encounter for a person-information message`() {
        val result = processor().process(Fixtures.PERSON_INFO_ONLY)

        val applied = assertIs<ProcessingResult.Applied>(result)
        assertTrue(applied.outcome.patientWritten)
        assertFalse(applied.outcome.encounterWritten)
        assertEquals(1, store.patients.size)
        assertEquals(0, store.encounters.size)
    }

    // --- permanent failures go to the dead-letter path ----------------------------------------

    @Test
    fun `rejects an unparseable payload`() {
        val rejected = assertIs<ProcessingResult.Rejected>(processor().process("{ not json"))

        assertEquals("unparseable-json", rejected.reason)
        // Only the exception's type, never its message: a JSON parser quotes the input it choked
        // on, and the input is PHI.
        assertFalse(rejected.detail.contains("MRN"), rejected.detail)
        assertEquals(0, store.writes)
    }

    @Test
    fun `rejects an envelope that fails validation`() {
        val payload = """
            {"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "patient":{},"encounter":{}}
        """

        val rejected = assertIs<ProcessingResult.Rejected>(processor().process(payload))

        assertEquals("invalid-envelope", rejected.reason)
        assertEquals("patient.medicalRecordNumber is blank", rejected.detail)
    }

    @Test
    fun `rejects an event whose timestamp cannot be mapped`() {
        val payload = """
            {"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"last Tuesday",
            "patient":{"medicalRecordNumber":"MRN-1"},"encounter":{}}
        """

        val rejected = assertIs<ProcessingResult.Rejected>(processor().process(payload))

        assertEquals("unmappable-event", rejected.reason)
    }

    @Test
    fun `rejects a null or blank payload`() {
        assertEquals("empty-payload", assertIs<ProcessingResult.Rejected>(processor().process(null)).reason)
        assertEquals("empty-payload", assertIs<ProcessingResult.Rejected>(processor().process("  ")).reason)
    }

    /**
     * The detail carried into a DLQ header and a log line names fields, never values. It comes from
     * exceptions this service authored for exactly that reason.
     */
    @Test
    fun `rejection details never carry patient data`() {
        val payload = """
            {"schemaVersion":"9.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "patient":{"medicalRecordNumber":"MRN-88421","familyName":"Núñez"},"encounter":{}}
        """

        val rejected = assertIs<ProcessingResult.Rejected>(processor().process(payload))

        assertFalse(rejected.detail.contains("MRN-88421"), rejected.detail)
        assertFalse(rejected.detail.contains("Núñez"), rejected.detail)
    }

    // --- transient failures are retried, then replayed ----------------------------------------

    @Test
    fun `retries a transient write failure and succeeds`() {
        store.failNext(
            ProjectionWriteException("failover", retryable = true),
            ProjectionWriteException("failover", retryable = true),
        )

        val result = processor().process(Fixtures.FULL_ADMIT)

        assertIs<ProcessingResult.Applied>(result)
        assertEquals(3, store.writes)
        // Exponential, ceiling applied: 100, 200.
        assertEquals(listOf(100L, 200L), slept)
    }

    @Test
    fun `gives up after the retry budget and lets the record replay`() {
        // Propagating rather than dead-lettering is the important half. A database that is down for
        // fifteen minutes must not turn into fifteen minutes of admissions parked on the DLQ and
        // an offset that has moved past all of them.
        store.failNext(
            ProjectionWriteException("down", retryable = true),
            ProjectionWriteException("down", retryable = true),
            ProjectionWriteException("down", retryable = true),
        )

        val error = assertFailsWith<ProjectionWriteException> { processor().process(Fixtures.FULL_ADMIT) }

        assertTrue(error.retryable)
        assertEquals(3, store.writes)
        assertEquals(listOf(100L, 200L), slept)
    }

    @Test
    fun `does not retry a failure the server already refused`() {
        // A document the server rejected on inspection — schema validation, size, an illegal field
        // name — fails identically every time. Retrying only delays the inevitable.
        store.failNext(ProjectionWriteException("rejected", retryable = false))

        assertFailsWith<ProjectionWriteException> { processor().process(Fixtures.FULL_ADMIT) }

        assertEquals(1, store.writes)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `honours a retry policy of a single attempt`() {
        store.failNext(ProjectionWriteException("down", retryable = true))

        assertFailsWith<ProjectionWriteException> {
            processor(RetryPolicy(attempts = 1, baseBackoffMillis = 50, maxBackoffMillis = 50))
                .process(Fixtures.FULL_ADMIT)
        }

        assertEquals(1, store.writes)
        assertTrue(slept.isEmpty())
    }

    // --- metrics ------------------------------------------------------------------------------

    @Test
    fun `counts a dead-letterable event as consumed but not mapped`() {
        val processor = processor()

        processor.process(Fixtures.FULL_ADMIT)
        processor.process("{ not json")

        assertTrue(metrics.render().contains("admissions_consumed_total 2"), metrics.render())
        assertTrue(metrics.render().contains("admissions_mapped_total 1"), metrics.render())
    }
}
