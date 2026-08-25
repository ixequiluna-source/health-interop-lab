package ai.firmus.interop.fhir

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters, exposed in Prometheus text format.
 *
 * Hand-rolled rather than pulled from a client library. There are seven numbers, they are all
 * monotonic counters, and the exposition format for a counter is four lines of string building
 * — which is a smaller thing to own than a metrics library's transitive dependency tree inside
 * a HIPAA-scoped service.
 *
 * The set is chosen so the two silent failure modes of this pipeline are visible:
 *
 *  - `admissions_dead_lettered_total` rising means messages are being *skipped*. Nothing else
 *    in the system says so; the consumer looks healthy and lag looks fine, because parking a
 *    message advances the offset. An alert on this counter is not optional.
 *  - `projections_stale_skipped_total` rising steadily means events are arriving out of order,
 *    which on a partitioned-by-MRN topic means the producer's keying has broken or someone has
 *    replayed history into the live topic.
 */
class Metrics {
    private val eventsConsumed = AtomicLong()
    private val eventsMapped = AtomicLong()
    private val eventsDeadLettered = AtomicLong()
    private val patientsUpserted = AtomicLong()
    private val encountersUpserted = AtomicLong()
    private val staleSkipped = AtomicLong()
    private val writeRetries = AtomicLong()

    fun recordConsumed() = eventsConsumed.incrementAndGet()

    fun recordMapped() = eventsMapped.incrementAndGet()

    fun recordDeadLettered() = eventsDeadLettered.incrementAndGet()

    fun recordPatientUpserted() = patientsUpserted.incrementAndGet()

    fun recordEncounterUpserted() = encountersUpserted.incrementAndGet()

    fun recordStaleSkipped() = staleSkipped.incrementAndGet()

    fun recordWriteRetry() = writeRetries.incrementAndGet()

    fun deadLetteredCount(): Long = eventsDeadLettered.get()

    fun staleSkippedCount(): Long = staleSkipped.get()

    fun render(): String = buildString {
        counter("admissions_consumed_total", "Admission events polled from Kafka.", eventsConsumed)
        counter("admissions_mapped_total", "Admission events mapped to FHIR resources.", eventsMapped)
        counter(
            "admissions_dead_lettered_total",
            "Admission events parked on the dead-letter topic. Alert on any increase.",
            eventsDeadLettered,
        )
        counter("projections_patient_upserted_total", "Patient projections written.", patientsUpserted)
        counter("projections_encounter_upserted_total", "Encounter projections written.", encountersUpserted)
        counter(
            "projections_stale_skipped_total",
            "Writes rejected by the recordedAt guard because stored data was newer.",
            staleSkipped,
        )
        counter("projection_write_retries_total", "Retries of a transient storage failure.", writeRetries)
    }

    private fun StringBuilder.counter(name: String, help: String, value: AtomicLong) {
        append("# HELP ").append(name).append(' ').append(help).append('\n')
        append("# TYPE ").append(name).append(" counter\n")
        append(name).append(' ').append(value.get()).append('\n')
    }
}
