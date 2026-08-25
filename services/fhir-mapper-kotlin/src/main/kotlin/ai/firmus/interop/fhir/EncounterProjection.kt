package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonObject
import org.bson.Document
import java.time.Instant
import java.util.Date

/**
 * The denormalised encounter document, shaped for `mongostore.encounterDoc` in the Go gateway.
 *
 * Denormalised on purpose: `medicalRecordNumber` is duplicated from the patient document so that
 * "every visit for this patient, newest first" is a single index scan on
 * `(medicalRecordNumber, admittedAt desc)` rather than a `$lookup`. The gateway streams that
 * query, and a join would have to be materialised before the first row could be sent.
 *
 * The location is stored as four flat fields rather than a nested object because the gateway
 * decodes it that way — `pointOfCare`, `room`, `bed` and `facility` are top-level in
 * `encounterDoc` and only reassembled into `patient.Location` afterwards.
 */
data class EncounterProjection(
    val id: String,
    val visitNumber: String,
    val medicalRecordNumber: String,
    val patientClass: String,
    val admittedAt: Instant,
    val attendingClinician: String,
    val pointOfCare: String,
    val room: String,
    val bed: String,
    val facility: String,
    val lastUpdated: Instant,
    val fhir: JsonObject,
) {
    /** The visit number and MRN are direct keys into the patient index; see PatientProjection. */
    override fun toString(): String =
        "EncounterProjection(id=$id, admittedAt=$admittedAt, lastUpdated=$lastUpdated, phi=redacted)"

    fun toDocument(): Document = Document()
        .append("_id", id)
        .append("visitNumber", visitNumber)
        .append("medicalRecordNumber", medicalRecordNumber)
        .append("patientClass", patientClass)
        .append("admittedAt", Date.from(admittedAt))
        .append("attendingClinician", attendingClinician)
        .append("pointOfCare", pointOfCare)
        .append("room", room)
        .append("bed", bed)
        .append("facility", facility)
        // Not read by the gateway — its encounterDoc has no matching field, and an unknown BSON
        // field is ignored by the Go driver. It exists for the write side: without a stored
        // recordedAt there is nothing for the staleness guard to compare an incoming event
        // against, and a replayed A01 would overwrite the A08 that corrected it.
        .append("lastUpdated", Date.from(lastUpdated))
        .append("fhir", fhir.toBsonDocument())
}
