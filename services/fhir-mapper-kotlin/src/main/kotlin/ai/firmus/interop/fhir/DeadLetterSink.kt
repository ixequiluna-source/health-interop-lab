package ai.firmus.interop.fhir

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Where a message goes when it can never be processed.
 *
 * ## The trade-off, stated plainly
 *
 * One malformed message on a partition is a choice between two bad outcomes. Retry it forever and
 * the partition stops: every patient whose MRN hashes to that partition stops being updated, lag
 * grows without bound, and the read model silently drifts away from reality for a subset of
 * patients that looks random from the outside. Skip it and one admission is missing from the
 * clinical store, but every other patient keeps flowing.
 *
 * This service skips — parks the message on a dead-letter topic and advances the offset. That is
 * only defensible with three conditions attached, and all three are enforced here or nearby:
 *
 *  1. **Only deterministic failures are parked.** A parse error or a mapping error will fail
 *     identically on every attempt. A Mongo timeout will not, and is retried rather than parked
 *     (see `ProjectionWriteException.retryable`). Dead-lettering a transient failure turns a
 *     fifteen-minute database failover into silent, permanent data loss.
 *  2. **The park must be durable before the offset advances.** If the DLQ write is fire-and-
 *     forget and the broker drops it, the message is gone with no record anywhere — worse than
 *     either original outcome. So the send is awaited, and a failure to park propagates.
 *  3. **Someone has to be watching.** `admissions_dead_lettered_total` exists for this. A
 *     dead-letter topic with no alert on it is a way of deleting messages slowly.
 *
 * The original key and value are republished byte-for-byte, with the diagnosis in headers rather
 * than wrapped around the payload. That is what makes replay-after-fix a `kafka-console-consumer`
 * piped into a producer instead of an unwrapping script somebody has to write under pressure.
 */
interface DeadLetterSink {
    /**
     * @throws ProjectionWriteException if the message could not be durably parked
     */
    fun park(record: ConsumerRecord<String, String>, reason: String, detail: String)
}

class KafkaDeadLetterSink(
    private val producer: Producer<String, String>,
    private val topic: String,
    private val log: Logger,
    private val sendTimeoutMillis: Long = 30_000,
    private val clock: () -> Instant = Instant::now,
) : DeadLetterSink {

    override fun park(record: ConsumerRecord<String, String>, reason: String, detail: String) {
        // The key is carried over so a replayed message lands on a partition consistent with the
        // MRN keying the producer uses, and so the DLQ itself stays ordered per patient.
        val outgoing: ProducerRecord<String, String> = ProducerRecord(topic, record.key(), record.value())
        // Header values are bytes on the wire; UTF-8 is the convention every Kafka tool assumes.
        outgoing.headers()
            .add(RecordHeader("dlq.reason", reason.toByteArray(Charsets.UTF_8)))
            .add(RecordHeader("dlq.detail", detail.toByteArray(Charsets.UTF_8)))
            .add(RecordHeader("dlq.source.topic", record.topic().toByteArray(Charsets.UTF_8)))
            .add(RecordHeader("dlq.source.partition", record.partition().toString().toByteArray(Charsets.UTF_8)))
            .add(RecordHeader("dlq.source.offset", record.offset().toString().toByteArray(Charsets.UTF_8)))
            .add(RecordHeader("dlq.parked.at", clock().toString().toByteArray(Charsets.UTF_8)))

        try {
            // Blocking on the ack is the point. The offset for this record is committed as soon as
            // park() returns, so returning before the broker has the message means losing it.
            producer.send(outgoing).get(sendTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Restore the flag: this thread is the consumer loop, and swallowing the interrupt
            // makes a shutdown hang until the grace period expires and the pod is killed.
            Thread.currentThread().interrupt()
            throw ProjectionWriteException("interrupted while parking a message", retryable = true, cause = e)
        } catch (e: ExecutionException) {
            throw ProjectionWriteException("dead-letter publish failed", retryable = true, cause = e)
        } catch (e: TimeoutException) {
            throw ProjectionWriteException("dead-letter publish timed out", retryable = true, cause = e)
        }

        // Coordinates and a reason code only. The payload that caused this is on the DLQ topic,
        // inside the same trust boundary as the source topic; the log pipeline is not.
        log.warn(
            "admission.dead_lettered",
            "reason" to reason,
            "detail" to detail,
            "sourceTopic" to record.topic(),
            "partition" to record.partition(),
            "offset" to record.offset(),
        )
    }
}
