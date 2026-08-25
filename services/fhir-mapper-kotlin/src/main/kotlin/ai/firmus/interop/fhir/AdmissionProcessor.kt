package ai.firmus.interop.fhir

import kotlinx.serialization.SerializationException

/**
 * What happened to one record. The consumer needs no more than this to decide whether the offset
 * may advance.
 */
sealed interface ProcessingResult {
    /** Mapped and written, or correctly skipped as stale. Safe to commit. */
    data class Applied(
        val eventId: String,
        val outcome: WriteOutcome,
    ) : ProcessingResult

    /** Permanently defective. The caller must park it before committing. */
    data class Rejected(val reason: String, val detail: String) : ProcessingResult
}

/**
 * Turns one Kafka payload into projections.
 *
 * This is the whole business path of the service, deliberately extracted from the consumer loop
 * and free of any Kafka type. That separation is what makes the interesting behaviour — the
 * classification of failures, the retry budget, the staleness outcome — testable without a broker,
 * and it leaves [AdmissionConsumer] thin enough that its own correctness (offsets, rebalance,
 * shutdown) can be read in one sitting.
 *
 * ## Failure classification
 *
 * Three outcomes, and the difference between them is the difference between a healthy pipeline and
 * a silent one:
 *
 *  - **Applied** — written, or rejected by the staleness guard, which is also a correct outcome.
 *  - **Rejected** — deterministically broken. Returned, not thrown, because the caller has to park
 *    it before advancing the offset and a return value makes that impossible to forget.
 *  - **Thrown** — transient. Propagates out of the loop without a commit, so the records replay.
 *    Losing an admission is worse than reprocessing one, and reprocessing is free here because the
 *    writes are idempotent.
 */
class AdmissionProcessor(
    private val mapper: FhirMapper,
    private val store: ProjectionStore,
    private val metrics: Metrics,
    private val log: Logger,
    private val retry: RetryPolicy,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {

    /**
     * @throws ProjectionWriteException when the write failed transiently and the retry budget ran
     *     out; the record must then be replayed, not committed
     */
    fun process(payload: String?): ProcessingResult {
        metrics.recordConsumed()

        if (payload.isNullOrBlank()) {
            // A null value on a non-compacted topic is not a tombstone, it is a producer bug or a
            // truncated write. There is nothing to map and nothing to retry.
            return ProcessingResult.Rejected("empty-payload", "record value was null or blank")
        }

        val event = try {
            AdmissionEvent.parse(payload)
        } catch (e: SerializationException) {
            // The exception's message can quote the offending input, which is PHI. Only its type
            // is carried into the DLQ header and the log line.
            return ProcessingResult.Rejected("unparseable-json", e.javaClass.simpleName)
        } catch (e: MappingException) {
            // Authored by this service; its messages name fields, never values.
            return ProcessingResult.Rejected("invalid-envelope", e.message ?: "validation failed")
        }

        val mapped = try {
            mapper.map(event)
        } catch (e: MappingException) {
            return ProcessingResult.Rejected("unmappable-event", e.message ?: "mapping failed")
        }
        metrics.recordMapped()

        val outcome = writeWithRetry(mapped)

        log.info(
            "admission.applied",
            "eventId" to event.eventId,
            "messageControlId" to event.messageControlId,
            "messageType" to event.messageType,
            "patientWritten" to outcome.patientWritten,
            "encounterWritten" to outcome.encounterWritten,
            "stale" to (outcome.patientStale || outcome.encounterStale),
        )

        return ProcessingResult.Applied(event.eventId, outcome)
    }

    /**
     * Retries a transient storage failure inside the poll cycle.
     *
     * Retrying here rather than by letting the record replay through Kafka is a deliberate choice
     * for *short* outages: a failover that lasts a second or two is absorbed without a rebalance,
     * without re-fetching, and without the group pausing. The budget is bounded and validated
     * against `max.poll.interval.ms` at startup ([Config.validate]) precisely because the failure
     * mode of getting it wrong is the opposite of what it looks like — a slow database evicts the
     * consumer, which triggers a rebalance, which makes every consumer redo its batch against the
     * same slow database.
     *
     * Anything longer than the budget is Kafka's problem, and Kafka handles it correctly: no
     * commit, no progress, replay on restart.
     */
    private fun writeWithRetry(mapped: MappedAdmission): WriteOutcome {
        var attempt = 1
        while (true) {
            try {
                return store.write(mapped)
            } catch (e: ProjectionWriteException) {
                if (!e.retryable || attempt >= retry.attempts) throw e
                metrics.recordWriteRetry()
                log.warn(
                    "projection.write.retry",
                    e,
                    "eventId" to mapped.eventId,
                    "attempt" to attempt,
                    "maxAttempts" to retry.attempts,
                )
                sleeper(retry.backoffMillis(attempt))
                attempt++
            }
        }
    }
}
