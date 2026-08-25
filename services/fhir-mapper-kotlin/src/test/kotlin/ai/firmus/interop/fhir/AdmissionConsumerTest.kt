package ai.firmus.interop.fhir

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The loop's own correctness: what gets committed, when, and what happens to a record that cannot
 * be processed. The mapping is covered elsewhere; these tests are about offsets.
 */
class AdmissionConsumerTest {

    private val topic = "clinical.admissions.v1"
    private val partition = TopicPartition(topic, 0)
    private val store = InMemoryProjectionStore()
    private val metrics = Metrics()
    private val parked = mutableListOf<Triple<Long, String, String>>()

    private val deadLetters = object : DeadLetterSink {
        var failWith: RuntimeException? = null

        override fun park(record: ConsumerRecord<String, String>, reason: String, detail: String) {
            failWith?.let { throw it }
            parked.add(Triple(record.offset(), reason, detail))
        }
    }

    private fun consumerUnder(
        mock: MockConsumer<String, String>,
        store: ProjectionStore = this.store,
    ) = AdmissionConsumer(
        consumer = mock,
        processor = AdmissionProcessor(
            mapper = Fixtures.mapper(),
            store = store,
            metrics = metrics,
            log = Fixtures.silentLogger(),
            retry = RetryPolicy(attempts = 1, baseBackoffMillis = 0, maxBackoffMillis = 0),
            sleeper = { },
        ),
        deadLetters = deadLetters,
        metrics = metrics,
        log = Fixtures.silentLogger(),
        topic = topic,
        pollTimeout = Duration.ofMillis(10),
        shutdownTimeout = Duration.ofMillis(100),
    )

    private fun mockConsumer(): MockConsumer<String, String> =
        MockConsumer<String, String>(OffsetResetStrategy.EARLIEST).apply {
            updateBeginningOffsets(mapOf(partition to 0L))
        }

    private fun record(offset: Long, value: String) =
        ConsumerRecord(topic, partition.partition(), offset, "MRN-88421", value)

    @Test
    fun `commits the offset after the record following the last one written`() {
        // The +1 is the classic off-by-one in this API. Omit it and every restart reprocesses one
        // record per partition forever; apply it twice and every restart skips one admission.
        val mock = mockConsumer()
        val consumer = consumerUnder(mock)
        val committed = mutableMapOf<TopicPartition, OffsetAndMetadata>()

        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.addRecord(record(0, Fixtures.FULL_ADMIT))
            mock.addRecord(record(1, Fixtures.PERSON_INFO_ONLY))
        }
        mock.schedulePollTask {
            committed.putAll(mock.committed(setOf(partition)))
            consumer.stop()
        }

        consumer.run()

        assertEquals(2L, committed[partition]?.offset())
        assertEquals(2, store.patients.size)
    }

    @Test
    fun `parks a poison message and advances past it`() {
        // The whole point of the dead-letter path: one malformed message must not stop the
        // partition, because every patient whose MRN hashes to it would stop being updated.
        val mock = mockConsumer()
        val consumer = consumerUnder(mock)
        val committed = mutableMapOf<TopicPartition, OffsetAndMetadata>()

        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.addRecord(record(0, "{ not json"))
            mock.addRecord(record(1, Fixtures.FULL_ADMIT))
        }
        mock.schedulePollTask {
            committed.putAll(mock.committed(setOf(partition)))
            consumer.stop()
        }

        consumer.run()

        assertEquals(1, parked.size)
        assertEquals(0L, parked.single().first)
        assertEquals("unparseable-json", parked.single().second)
        assertEquals(2L, committed[partition]?.offset())
        // The good record after the poison one was still processed.
        assertEquals(1, store.patients.size)
        assertTrue(metrics.render().contains("admissions_dead_lettered_total 1"), metrics.render())
    }

    @Test
    fun `does not advance past a message it could not park`() {
        // Parking has to be durable before the offset moves. If the DLQ write fails and the offset
        // advanced anyway, the message is gone with no copy anywhere — worse than either blocking
        // or skipping.
        val mock = mockConsumer()
        val consumer = consumerUnder(mock)
        deadLetters.failWith = ProjectionWriteException("dlq unavailable", retryable = true)

        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.addRecord(record(0, "{ not json"))
        }

        assertFailsWith<ProjectionWriteException> { consumer.run() }

        assertTrue(mock.committed(setOf(partition)).isEmpty(), "nothing may be committed")
    }

    @Test
    fun `does not commit when a write fails transiently`() {
        // No commit means the batch replays, which is the correct response to a storage outage and
        // is only safe because the writes are idempotent.
        val mock = mockConsumer()
        val failing = object : ProjectionStore {
            override fun write(admission: MappedAdmission): WriteOutcome =
                throw ProjectionWriteException("mongo down", retryable = true)
        }
        val consumer = consumerUnder(mock, failing)

        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.addRecord(record(0, Fixtures.FULL_ADMIT))
        }

        assertFailsWith<ProjectionWriteException> { consumer.run() }

        assertTrue(mock.committed(setOf(partition)).isEmpty(), "nothing may be committed")
        assertTrue(parked.isEmpty(), "a transient failure must never be dead-lettered")
    }

    @Test
    fun `stops cleanly when woken and reports that it is no longer running`() {
        val mock = mockConsumer()
        val consumer = consumerUnder(mock)

        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            consumer.stop()
        }

        consumer.run()

        assertEquals(false, consumer.isRunning())
        assertTrue(mock.closed())
    }
}
