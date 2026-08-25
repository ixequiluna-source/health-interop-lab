package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonObject
import org.bson.Document
import java.time.Instant
import java.util.Date

/** One assigning-authority-scoped identifier, shaped as `interop.v1.Identifier`. */
data class ProjectedIdentifier(val system: String, val value: String, val type: String) {
    /** Identifier values are PHI; see [PatientProjection.toString]. */
    override fun toString(): String = "ProjectedIdentifier(system=$system, type=$type, value=redacted)"

    fun toDocument(): Document = Document()
        .append("system", system)
        .append("value", value)
        .append("type", type)
}

/**
 * The denormalised patient document the Go read gateway queries.
 *
 * ## The contract
 *
 * Field names here are not a design choice — they are a wire contract with
 * `../patient-gateway-go/internal/mongostore/mongo.go`, which decodes into a struct with
 * explicit `bson:` tags and builds indexes on `medicalRecordNumber`, `searchTerms` and the
 * `(foldedFamilyName, foldedGivenName, medicalRecordNumber)` sort key. A rename on this side is
 * not a refactor; it is an outage in which the gateway returns empty pages and reports no error,
 * because a missing BSON field decodes to a zero value rather than failing.
 *
 * `foldedFamilyName` and `foldedGivenName` exist so the gateway's sort can be served from an
 * index. Sorting on the raw names would order "Álvarez" after "Zamora" under a binary collation
 * and would make pagination inconsistent with the accent-insensitive matching used to select the
 * rows in the first place — consecutive pages would then repeat and omit patients.
 *
 * ## PHI
 *
 * The generated `toString` of a data class renders every field, and this type holds a name, a
 * birth date and an MRN. One `"projection" to projection` in a log call, or one exception whose
 * message interpolates it, is a disclosure. Overriding `toString` makes the leak impossible
 * rather than merely discouraged.
 */
data class PatientProjection(
    val id: String,
    val medicalRecordNumber: String,
    val identifiers: List<ProjectedIdentifier>,
    val familyName: String,
    val givenName: String,
    val birthDate: String,
    val administrativeSex: String,
    val searchTerms: List<String>,
    val foldedFamilyName: String,
    val foldedGivenName: String,
    val lastUpdated: Instant,
    val fhir: JsonObject,
) {
    override fun toString(): String = "PatientProjection(id=$id, lastUpdated=$lastUpdated, phi=redacted)"

    /**
     * `_id` is the deterministic resource id, not a generated ObjectId.
     *
     * That makes the primary key itself the idempotency key: a replayed event upserts onto the
     * same `_id` and cannot produce a second row. It also means the upsert is served by the
     * `_id` index, which every collection has, rather than by the secondary unique index on
     * `medicalRecordNumber` — that index stays as the invariant that says two documents can
     * never claim the same patient.
     */
    fun toDocument(): Document = Document()
        .append("_id", id)
        .append("medicalRecordNumber", medicalRecordNumber)
        .append("identifiers", identifiers.map { it.toDocument() })
        .append("familyName", familyName)
        .append("givenName", givenName)
        .append("birthDate", birthDate)
        .append("administrativeSex", administrativeSex)
        .append("searchTerms", searchTerms)
        .append("foldedFamilyName", foldedFamilyName)
        .append("foldedGivenName", foldedGivenName)
        // BSON has one date type with millisecond resolution. `lastUpdated` is truncated to
        // milliseconds where it is parsed (see Times.parseTimestamp) so that the value written
        // here and the value the staleness guard compares against are the same value.
        .append("lastUpdated", Date.from(lastUpdated))
        .append("fhir", fhir.toBsonDocument())
}
