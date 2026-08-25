package ai.firmus.interop.fhir

import java.time.Instant

/**
 * A storage failure.
 *
 * The [retryable] flag is the only thing the consumer needs from the storage layer to make the
 * right decision, and it is the storage layer that knows the answer. Getting this split wrong
 * is the expensive mistake in a dead-letter design: dead-letter a transient failure and a
 * fifteen-minute database failover silently discards every admission that arrived during it;
 * retry a permanent one and the partition stops moving forever.
 */
class ProjectionWriteException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** What a write actually did. Both flags are false when the staleness guard rejected the write. */
data class WriteOutcome(
    val patientWritten: Boolean,
    val encounterWritten: Boolean,
    val patientStale: Boolean,
    val encounterStale: Boolean,
)

/**
 * Where projections go.
 *
 * An interface with exactly one production implementation, which is usually a smell — the reason
 * it earns its place is that it lets the consumer's ordering, retry and dead-letter behaviour be
 * tested against a store that can be told to fail transiently, permanently, or not at all,
 * without a database. Those are the paths that a Testcontainers suite exercises least and that
 * break most expensively.
 */
interface ProjectionStore {
    /**
     * Writes the patient, then the encounter.
     *
     * @throws ProjectionWriteException on any storage failure
     */
    fun write(admission: MappedAdmission): WriteOutcome
}

/**
 * The staleness rule, in one place, in plain Kotlin.
 *
 * ## What it is for
 *
 * Idempotency and ordering are two different problems and this service solves them with two
 * different mechanisms. The deterministic `_id` stops a replay from *duplicating* a row. This
 * guard stops an *older* event from overwriting newer data — which a deterministic id does not
 * prevent at all, because a replayed A01 admit and the A08 update that corrected it target the
 * same document, and whichever arrives last wins.
 *
 * Older events do arrive. An operator replaying a topic offset to recover from a bad deploy, a
 * consumer group reset, a partition reassignment that re-reads uncommitted records, a second
 * mapper instance started against the same data — all of them deliver history into a store that
 * already holds the present. Without the guard, the correction a nurse made this morning is
 * reverted by a message from Tuesday and nothing anywhere reports an error.
 *
 * ## Why the comparison is `<=` and not `<`
 *
 * A tie means two events carry the same `recordedAt`, which on a topic partitioned by MRN means
 * they were produced in quick succession for the same patient and were delivered in order.
 * Within a partition, arrival order *is* the authoritative order, so the later arrival should
 * win — and it does, because a tie passes the guard. Rejecting ties instead would drop the second
 * of two updates made in the same second, and would do so invisibly.
 *
 * Letting a tie through does not reintroduce duplicates: the write still targets the same
 * deterministic `_id`, so a replayed event rewrites identical bytes onto the document it wrote
 * the first time.
 */
fun isStale(storedLastUpdated: Instant?, incomingRecordedAt: Instant): Boolean =
    storedLastUpdated != null && storedLastUpdated.isAfter(incomingRecordedAt)
