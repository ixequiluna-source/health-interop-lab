package ai.firmus.interop.fhir

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The consume-map-write-commit loop.
 *
 * ## Manual commit, after the write
 *
 * `enable.auto.commit` is false and offsets are committed only once the corresponding writes have
 * been acknowledged by Mongo. Auto-commit runs on a timer inside `poll()`, which means it can
 * commit offsets for records that have been *fetched* and not yet processed: crash in that window
 * and those admissions are never written and never redelivered. They are simply gone, and nothing
 * reports an error — lag is zero, the consumer is healthy, and a patient is missing from the
 * index.
 *
 * Committing after the write inverts the failure: a crash between the write and the commit
 * replays records that were already applied. That is exactly the case the deterministic ids and
 * the staleness guard are built for, so the cost of the safe direction is nothing.
 *
 * This is at-least-once, and it is the strongest guarantee available without making the Kafka
 * offset and the Mongo document part of one atomic commit. The pipeline is not exactly-once; it
 * is at-least-once plus convergence, which for a read model is the same observable result.
 *
 * ## Ordering
 *
 * Records are processed strictly in the order `poll()` returned them, one at a time. The upstream
 * keys by MRN, so all of a patient's events land on one partition, and a partition is owned by one
 * consumer: sequential processing is what turns that keying into an actual ordering guarantee. Any
 * parallelism added here — a thread pool over the batch, a `launch{}` per record — silently
 * removes it, and the A08 that corrects a name can then be overwritten by the A01 that preceded
 * it. (The staleness guard would catch most of that. It is a safety net, not a licence.)
 */
class AdmissionConsumer(
    private val consumer: Consumer<String, String>,
    private val processor: AdmissionProcessor,
    private val deadLetters: DeadLetterSink,
    private val metrics: Metrics,
    private val log: Logger,
    private val topic: String,
    private val pollTimeout: Duration,
    private val shutdownTimeout: Duration,
) : ConsumerRebalanceListener {

    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val lastPollAt = AtomicReference(Instant.EPOCH)

    /**
     * Offsets that have been written but not yet committed, keyed by partition.
     *
     * Held per partition rather than as a single "last offset" because a poll returns records from
     * several partitions interleaved, and progress on one says nothing about progress on another.
     */
    private val pending = mutableMapOf<TopicPartition, OffsetAndMetadata>()

    /** True once the loop has started and until it has finished; the liveness probe reads this. */
    fun isRunning(): Boolean = running.get()

    /** When the loop last returned from `poll()`; the readiness probe reads this. */
    fun lastPollAt(): Instant = lastPollAt.get()

    /**
     * Runs until [stop] is called. Returns normally on a clean shutdown; throws on a fatal error.
     */
    fun run() {
        running.set(true)
        consumer.subscribe(listOf(topic), this)
        log.info("consumer.started", "topic" to topic)

        try {
            while (!stopping.get()) {
                val records = consumer.poll(pollTimeout)
                lastPollAt.set(Instant.now())
                if (records.isEmpty) continue

                for (record in records) {
                    handle(record)
                }
                commitPending("batch")
            }
        } catch (e: WakeupException) {
            // wakeup() is the only way to interrupt a blocking poll, so it is also how shutdown is
            // signalled. It is an error only if nobody asked to stop.
            if (!stopping.get()) throw e
            log.info("consumer.woken_for_shutdown")
        } finally {
            running.set(false)
            shutdown()
        }
    }

    /**
     * Asks the loop to stop. Safe to call from a signal handler thread.
     *
     * The flag is set *before* `wakeup()`: the reverse order races with a poll that is about to
     * start, which would consume the wakeup, see the flag unset, and block for another poll
     * timeout.
     */
    fun stop() {
        stopping.set(true)
        consumer.wakeup()
    }

    private fun handle(record: ConsumerRecord<String, String>) {
        when (val result = processor.process(record.value())) {
            is ProcessingResult.Applied -> markProcessed(record)

            is ProcessingResult.Rejected -> {
                // park() blocks until the broker acknowledges. If it throws, the offset is not
                // marked and the record replays — the message is never skipped without a durable
                // copy of it existing somewhere else first.
                deadLetters.park(record, result.reason, result.detail)
                metrics.recordDeadLettered()
                markProcessed(record)
            }
        }
    }

    /**
     * A committed offset is the offset of the *next* record to read, not the last one read.
     *
     * The `+ 1` is the classic off-by-one in this API, and getting it wrong is quiet in both
     * directions: omit it and every restart reprocesses one record per partition forever; add it
     * twice and every restart skips one admission.
     */
    private fun markProcessed(record: ConsumerRecord<String, String>) {
        pending[TopicPartition(record.topic(), record.partition())] = OffsetAndMetadata(record.offset() + 1)
    }

    private fun commitPending(reason: String) {
        if (pending.isEmpty()) return
        consumer.commitSync(pending.toMap())
        log.debug("consumer.committed", "reason" to reason, "partitions" to pending.size)
        pending.clear()
    }

    /**
     * Commits work already done before the partitions move to another consumer.
     *
     * Invoked by the client from inside `poll()` while the group is rebalancing, which is the one
     * window in which committing from a callback is correct and expected. Without it, everything
     * processed since the last commit is redone by whichever consumer picks the partition up —
     * harmless for correctness here, because the writes are idempotent, but it is duplicated work
     * on every rebalance and it grows with batch size.
     */
    override fun onPartitionsRevoked(partitions: MutableCollection<TopicPartition>) {
        log.info("consumer.partitions_revoked", "count" to partitions.size)
        try {
            commitPending("revoked")
        } catch (e: RuntimeException) {
            // A failed commit during a rebalance is survivable: the records replay. Letting the
            // exception escape the callback would fail the whole poll and take the consumer down
            // for something the design already tolerates.
            log.warn("consumer.commit_on_revoke_failed", e)
            pending.clear()
        }
    }

    override fun onPartitionsAssigned(partitions: MutableCollection<TopicPartition>) {
        log.info("consumer.partitions_assigned", "count" to partitions.size)
    }

    /**
     * Called when partitions were taken away without a chance to commit — the consumer was evicted
     * for missing `max.poll.interval.ms`, or its session expired.
     *
     * Deliberately does *not* commit. By the time this runs another consumer already owns the
     * partitions and may have advanced past these offsets; committing here would move their
     * committed position backwards and cause a real, unbounded reprocessing loop. The pending work
     * is dropped and those records are simply redelivered to whoever owns them now.
     */
    override fun onPartitionsLost(partitions: MutableCollection<TopicPartition>) {
        log.warn("consumer.partitions_lost", "count" to partitions.size)
        partitions.forEach { pending.remove(it) }
    }

    private fun shutdown() {
        try {
            // Last chance to record work that is already durable in Mongo. Skipping it is safe but
            // wasteful: those records would be reprocessed on the next start.
            commitPending("shutdown")
        } catch (e: RuntimeException) {
            log.warn("consumer.final_commit_failed", e)
        }
        try {
            // close() with a timeout, not close(): it sends a LeaveGroup so the group rebalances
            // immediately instead of waiting out session.timeout.ms with a dead member, and the
            // bound stops a broker that is not answering from holding the pod past its termination
            // grace period — at which point the kill is a SIGKILL and nothing else gets to run.
            consumer.close(shutdownTimeout)
        } catch (e: RuntimeException) {
            log.warn("consumer.close_failed", e)
        }
        log.info("consumer.stopped")
    }
}
