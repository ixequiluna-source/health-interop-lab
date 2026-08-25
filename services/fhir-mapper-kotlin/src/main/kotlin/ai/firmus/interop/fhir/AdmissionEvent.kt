package ai.firmus.interop.fhir

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Raised when an event is structurally present but cannot be turned into resources.
 *
 * Messages are written to be safe in a log line: they name fields, never values. `"patient
 * .medicalRecordNumber is blank"` is a usable diagnostic; `"invalid MRN 'MRN-88421'"` is PHI
 * in a log aggregator that almost certainly has a different retention and access regime than
 * the clinical store.
 *
 * Distinct from a transport or storage failure on purpose: a MappingException is deterministic,
 * so retrying it forever would wedge the partition. It is the only failure class that gets
 * dead-lettered.
 */
class MappingException(message: String) : RuntimeException(message)

/**
 * The canonical admission event, mirroring `AdmissionEvent` in the Java ingest service.
 *
 * The producer omits empty fields rather than emitting nulls (`Json.optional` on that side),
 * so every optional field defaults to the empty string and "absent" and "sent empty" collapse
 * to the same thing here. That collapse is safe for this consumer — it treats blank as "no
 * value" everywhere — but it is worth knowing that the distinction exists upstream and is
 * meaningful there, in ADT update messages.
 *
 * `toString` is overridden throughout this file. A generated data-class `toString` renders
 * every field, which means one careless `log.info("bad event", "e" to event)` or one exception
 * whose message interpolates the object publishes a patient's name and MRN into the log
 * pipeline. Redacting at the type makes the safe thing the default thing.
 */
@Serializable
data class AdmissionEvent(
    val schemaVersion: String = "",
    val eventId: String = "",
    val messageControlId: String = "",
    val messageType: String = "",
    val sendingApplication: String = "",
    val sendingFacility: String = "",
    val recordedAt: String = "",
    val patient: Patient = Patient(),
    val encounter: Encounter = Encounter(),
) {
    @Serializable
    data class Patient(
        val medicalRecordNumber: String = "",
        val otherIdentifiers: List<String> = emptyList(),
        val familyName: String = "",
        val givenName: String = "",
        val birthDate: String = "",
        val administrativeSex: String = "",
    ) {
        override fun toString(): String = "AdmissionEvent.Patient(redacted)"
    }

    @Serializable
    data class Encounter(
        val visitNumber: String = "",
        val patientClass: String = "",
        val admitDateTime: String = "",
        val attendingClinician: String = "",
        val pointOfCare: String = "",
        val room: String = "",
        val bed: String = "",
        val facility: String = "",
    ) {
        /**
         * Encounter data is not patient-identifying on its own, but the visit number is a
         * direct key back to the patient index and the attending clinician is personal data
         * about a member of staff. Neither belongs in an application log either.
         */
        override fun toString(): String = "AdmissionEvent.Encounter(redacted)"

        /**
         * True when the message carried nothing that describes a visit.
         *
         * ADT A28 and A31 are person-information messages: they update demographics and carry
         * no PV1 at all. Projecting an encounter from one produces a visit with no number, no
         * class and no admission time — a phantom admission that a bed-occupancy report cannot
         * reconcile and that nobody can trace back to a source message.
         */
        fun isEmpty(): Boolean =
            visitNumber.isBlank() && patientClass.isBlank() && admitDateTime.isBlank() &&
                attendingClinician.isBlank() && pointOfCare.isBlank() && room.isBlank() &&
                bed.isBlank() && facility.isBlank()
    }

    override fun toString(): String =
        "AdmissionEvent(eventId=$eventId, messageControlId=$messageControlId, " +
            "messageType=$messageType, schemaVersion=$schemaVersion, recordedAt=$recordedAt)"

    /**
     * The ADT trigger event, e.g. `A01` from `ADT^A01`.
     *
     * Returned as an empty string when the message type is missing or malformed rather than
     * throwing: the trigger only refines the encounter status, and a missing one should not
     * dead-letter an otherwise complete admission.
     */
    fun triggerEvent(): String = messageType.substringAfter('^', "").take(3).uppercase()

    companion object {
        /**
         * The schema major version this consumer understands.
         *
         * Checked rather than assumed. A producer rolled forward to 2.x may have moved or
         * re-typed a field; silently mapping it with 1.x rules writes plausible-looking wrong
         * data into the clinical read model, which is far worse than a visible dead-letter
         * backlog that says "the mapper needs upgrading".
         */
        const val SUPPORTED_MAJOR_VERSION: String = "1"

        private val JSON = Json {
            // A producer adding a field must not stop the pipeline. Additive schema change is
            // the normal case, and refusing it means every downstream service has to be
            // deployed before the producer can be.
            ignoreUnknownKeys = true

            // The Java producer omits empty fields, but nothing stops a future producer from
            // sending an explicit null. Coercion maps null onto the declared default instead
            // of throwing, so `"givenName": null` and an absent `givenName` behave alike.
            coerceInputValues = true

            // Strict about everything else: unquoted keys and trailing commas are signs of a
            // hand-edited or corrupted payload, and this is a clinical data path.
            isLenient = false
        }

        /**
         * Parses and validates one Kafka payload.
         *
         * @throws SerializationException when the bytes are not the expected JSON
         * @throws MappingException when the envelope is well-formed but unusable
         */
        fun parse(payload: String): AdmissionEvent {
            val event = JSON.decodeFromString(serializer(), payload)
            event.validate()
            return event
        }
    }

    /**
     * Checks the invariants the rest of the pipeline is allowed to assume.
     *
     * Everything here is a *permanent* defect in the message: no amount of retrying makes an
     * event without an MRN mappable. That is what makes dead-lettering the right response and
     * why these checks are separated from the mapping itself.
     */
    fun validate() {
        val major = schemaVersion.substringBefore('.')
        if (major != SUPPORTED_MAJOR_VERSION) {
            throw MappingException(
                "unsupported schemaVersion major '$major'; this mapper handles $SUPPORTED_MAJOR_VERSION.x",
            )
        }
        if (eventId.isBlank()) {
            throw MappingException("eventId is blank")
        }
        if (recordedAt.isBlank()) {
            throw MappingException("recordedAt is blank")
        }
        if (patient.medicalRecordNumber.isBlank()) {
            // The MRN is the primary key of the read model and the Kafka partition key. An
            // event without one cannot be de-duplicated, cannot be ordered against its
            // siblings, and cannot be looked up again.
            throw MappingException("patient.medicalRecordNumber is blank")
        }
    }
}
