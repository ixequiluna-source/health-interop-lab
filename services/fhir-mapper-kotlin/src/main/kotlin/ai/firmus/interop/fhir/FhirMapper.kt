package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Emits a FHIR `Coding` array under the key `coding` on the current object. */
private fun JsonObjectBuilder.coding(value: Coding) {
    putJsonArray("coding") {
        addJsonObject {
            put("system", value.system)
            put("code", value.code)
            put("display", value.display)
        }
    }
}

/** Emits a single-coding `CodeableConcept` under [key]. */
private fun JsonObjectBuilder.codeableConcept(key: String, value: Coding) {
    putJsonObject(key) { coding(value) }
}

/** One FHIR `Coding`. */
data class Coding(val system: String, val code: String, val display: String)

/**
 * Terminology this mapper emits, spelled out once.
 *
 * These URIs are canonical identifiers, not addresses — nothing dereferences them. Getting one
 * wrong produces a resource that validates structurally and means something different, which is
 * the failure mode that survives a code review and is caught by a terminology server months
 * later.
 */
object FhirSystems {
    const val V2_0203_IDENTIFIER_TYPE = "http://terminology.hl7.org/CodeSystem/v2-0203"
    const val V3_ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode"
    const val V3_PARTICIPATION_TYPE = "http://terminology.hl7.org/CodeSystem/v3-ParticipationType"
    const val V3_NULL_FLAVOR = "http://terminology.hl7.org/CodeSystem/v3-NullFlavor"
}

/** HL7 v2 table 0203 identifier types, the subset this pipeline can actually distinguish. */
object IdentifierTypes {
    val MEDICAL_RECORD_NUMBER = Coding(FhirSystems.V2_0203_IDENTIFIER_TYPE, "MR", "Medical record number")

    /**
     * PID-3 repetitions after the first are secondary identifiers whose assigning authority the
     * upstream flattens away, so their kind is genuinely unknown. `PI` — "patient internal
     * identifier" — is the honest code for that. Guessing `SS` or `DL` from the digit count is
     * how a driving licence number ends up typed as a social security number.
     */
    val PATIENT_INTERNAL = Coding(FhirSystems.V2_0203_IDENTIFIER_TYPE, "PI", "Patient internal identifier")

    val VISIT_NUMBER = Coding(FhirSystems.V2_0203_IDENTIFIER_TYPE, "VN", "Visit number")
}

/** HL7 v3 ActCode encounter classes, plus the null flavour used when PV1-2 says nothing usable. */
object EncounterClasses {
    val INPATIENT = Coding(FhirSystems.V3_ACT_CODE, "IMP", "inpatient encounter")
    val AMBULATORY = Coding(FhirSystems.V3_ACT_CODE, "AMB", "ambulatory")
    val EMERGENCY = Coding(FhirSystems.V3_ACT_CODE, "EMER", "emergency")
    val PRE_ADMISSION = Coding(FhirSystems.V3_ACT_CODE, "PRENC", "pre-admission")

    /**
     * `Encounter.class` is 1..1 in R4, so it cannot simply be omitted when PV1-2 is blank or
     * carries a code this mapper does not recognise. The v3 null flavour `UNK` is the modelled
     * way to say "required, and not known" — as opposed to picking `AMB`, which asserts an
     * outpatient visit that nobody recorded.
     */
    val UNKNOWN = Coding(FhirSystems.V3_NULL_FLAVOR, "UNK", "unknown")
}

/**
 * Deterministic resource identifiers.
 *
 * ## Why they are derived rather than generated
 *
 * Kafka delivery is at-least-once. A consumer that crashes between writing to Mongo and
 * committing its offset re-reads the same records on restart, and a broker that fails a
 * `commitSync` after the coordinator already applied it produces the same replay. This is not
 * an edge case — it is the normal, expected behaviour of the transport, and it happens every
 * time a pod is rescheduled.
 *
 * With a random id, each replay inserts another document, and one admission becomes three rows
 * in the encounter list a clinician is reading. With an id derived from the event's own natural
 * key, a replay is an upsert onto the document already there: the second write is a no-op that
 * happens to rewrite identical bytes. That is what makes the pipeline *effectively* once, and
 * it costs nothing but a hash.
 *
 * SHA-256 hex is 64 characters, which is exactly the FHIR `id` limit, and it uses only
 * characters the `[A-Za-z0-9\-\.]{1,64}` grammar allows. Hashing rather than concatenating the
 * natural key also keeps identifiers out of the resource id — an MRN embedded in a URL ends up
 * in web-server access logs, which is the same PHI-in-logs problem in a different place.
 */
object ResourceIds {
    fun patient(medicalRecordNumber: String): String = sha256Hex("Patient|$medicalRecordNumber")

    /**
     * The encounter id.
     *
     * Keyed on the visit number when the sender supplied one, because that is the identity of
     * the visit: an A01 admit and the A08 update that follows it describe one encounter and
     * must converge onto one document.
     *
     * When PV1-19 is absent there is no visit identity to key on, so the id falls back to the
     * event id. That is weaker — two messages describing the same visit will produce two
     * documents — but it is still idempotent under replay, which is the property that protects
     * the read model. The alternative, keying on the admit timestamp, silently merges two
     * different visits that started in the same second.
     */
    fun encounter(medicalRecordNumber: String, visitNumber: String, eventId: String): String =
        if (visitNumber.isNotBlank()) {
            sha256Hex("Encounter|$medicalRecordNumber|$visitNumber")
        } else {
            sha256Hex("Encounter|event|$eventId")
        }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/** The result of mapping one admission event. */
data class MappedAdmission(
    val eventId: String,
    val patient: PatientProjection,
    /** Null for person-information messages (A28/A31), which carry no visit. */
    val encounter: EncounterProjection?,
)

/**
 * Maps the canonical admission envelope onto FHIR R4 `Patient` and `Encounter` resources and
 * the denormalised documents the Go read gateway queries.
 *
 * ## Why the resources are built by hand
 *
 * There is no HAPI dependency. The mapping surface here is two resources and about a dozen
 * elements, and the interesting work is entirely in the decisions — which HL7 code becomes
 * which FHIR code, what to do when a required element has no source value, when *not* to assert
 * something. A structure library does none of that; it would add a large dependency to a
 * HIPAA-scoped service and leave every one of those decisions still to be made, just less
 * visibly.
 *
 * ## The rule that governs the whole file
 *
 * An element is emitted only when the source message actually said it. Absent is not the same
 * as unknown, and neither is the same as false. A `gender` of `unknown` on a patient whose
 * PID-8 was empty asserts that someone asked and did not find out; omitting the element says
 * the message did not carry it. Downstream, one of those two is a data-quality report and the
 * other is a clinical statement.
 */
class FhirMapper(
    private val identifierSystemBase: String,
    private val defaultAssigningAuthority: String,
    private val facilityZone: ZoneId,
) {
    constructor(config: Config) : this(
        config.identifierSystemBase,
        config.defaultAssigningAuthority,
        config.facilityZone,
    )

    /**
     * @throws MappingException when the event cannot produce a coherent projection
     */
    fun map(event: AdmissionEvent): MappedAdmission {
        val recordedAt = parseTimestamp(event.recordedAt, facilityZone)
            ?: throw MappingException("recordedAt is not a parseable timestamp")

        val patientId = ResourceIds.patient(event.patient.medicalRecordNumber)
        val authority = assigningAuthority(event)
        val systemUri = identifierSystemUri(authority)

        val patientResource = buildPatientResource(event, patientId, systemUri, recordedAt)
        val patient = PatientProjection(
            id = patientId,
            medicalRecordNumber = event.patient.medicalRecordNumber,
            identifiers = buildIdentifiers(event.patient, authority),
            familyName = event.patient.familyName,
            givenName = event.patient.givenName,
            // The read model keeps the HL7 date string, partial precision and all. The gateway's
            // proto declares birth_date as a string for the same reason: a date type there would
            // force somebody to invent a day.
            birthDate = normalisePartialDate(event.patient.birthDate) ?: "",
            // And it keeps the raw HL7 table 0001 code, because that is what `interop.v1.Patient
            // .administrative_sex` is documented to carry. The FHIR-normalised value lives in the
            // resource under `fhir.gender`; the two are different vocabularies and conflating
            // them would change the gateway's contract.
            administrativeSex = event.patient.administrativeSex.trim().uppercase(),
            searchTerms = buildSearchTerms(event.patient),
            foldedFamilyName = fold(event.patient.familyName),
            foldedGivenName = fold(event.patient.givenName),
            lastUpdated = recordedAt,
            fhir = patientResource,
        )

        val encounter = if (event.encounter.isEmpty()) {
            null
        } else {
            val encounterId = ResourceIds.encounter(
                event.patient.medicalRecordNumber,
                event.encounter.visitNumber,
                event.eventId,
            )
            EncounterProjection(
                id = encounterId,
                visitNumber = event.encounter.visitNumber,
                medicalRecordNumber = event.patient.medicalRecordNumber,
                patientClass = event.encounter.patientClass.trim().uppercase(),
                // Falls back to the event's own recording time when the sender gave no admit
                // timestamp. The gateway sorts a patient's encounters by `admittedAt` descending,
                // so a missing value would sort a live admission to the bottom of the list. The
                // approximation is confined to the read model: the FHIR resource below omits
                // `period` entirely rather than publishing a time nobody recorded.
                admittedAt = parseTimestamp(event.encounter.admitDateTime, facilityZone) ?: recordedAt,
                attendingClinician = event.encounter.attendingClinician,
                pointOfCare = event.encounter.pointOfCare,
                room = event.encounter.room,
                bed = event.encounter.bed,
                facility = event.encounter.facility,
                lastUpdated = recordedAt,
                fhir = buildEncounterResource(event, encounterId, patientId, systemUri, recordedAt),
            )
        }

        return MappedAdmission(eventId = event.eventId, patient = patient, encounter = encounter)
    }

    /**
     * The assigning authority for the patient's identifiers.
     *
     * The upstream flattens PID-3 to its first component, dropping the authority the segment
     * carried, so MSH-4 (sending facility) is the best available answer — and it is the right
     * one in practice, because the facility that sent the ADT is the facility that issued the
     * MRN. `interop.v1.Identifier.system` documents itself as exactly this: "Assigning
     * authority, e.g. HGS or IMSS".
     */
    private fun assigningAuthority(event: AdmissionEvent): String =
        event.sendingFacility.trim().ifEmpty { defaultAssigningAuthority }

    /**
     * The FHIR `identifier.system`, which must be a URI.
     *
     * This is deliberately not the same string as the read model's `system`. The gRPC contract
     * declares a bare authority name; FHIR declares a URI. Writing the bare name into the
     * resource would produce an invalid identifier, and writing the URI into the read model
     * would break the gateway's documented shape. The sanitiser exists because facility names
     * contain spaces and slashes, and `urn:firmus:identifier:HOSPITAL GENERAL` is not a URI.
     */
    internal fun identifierSystemUri(authority: String): String =
        "$identifierSystemBase:" + authority.replace(UNSAFE_URI_CHARS, "-")

    private fun buildIdentifiers(
        patient: AdmissionEvent.Patient,
        authority: String,
    ): List<ProjectedIdentifier> {
        val identifiers = mutableListOf(
            ProjectedIdentifier(authority, patient.medicalRecordNumber, IdentifierTypes.MEDICAL_RECORD_NUMBER.code),
        )
        patient.otherIdentifiers
            .filter { it.isNotBlank() }
            .mapTo(identifiers) { ProjectedIdentifier(authority, it, IdentifierTypes.PATIENT_INTERNAL.code) }
        return identifiers
    }

    private fun buildPatientResource(
        event: AdmissionEvent,
        patientId: String,
        systemUri: String,
        recordedAt: Instant,
    ): JsonObject {
        val patient = event.patient
        val birthDate = normalisePartialDate(patient.birthDate)
        val gender = mapGender(patient.administrativeSex)
        val given = patient.givenName.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val hasName = patient.familyName.isNotBlank() || given.isNotEmpty()

        return buildJsonObject {
            put("resourceType", "Patient")
            put("id", patientId)
            putJsonObject("meta") {
                // Normalised through the parsed instant rather than copied from the envelope:
                // FHIR `instant` requires an offset and at least second precision, and the
                // envelope only promises "whatever the producer wrote".
                put("lastUpdated", toFhirInstant(recordedAt))
                // Provenance that is safe to publish: the event id ties a resource back to the
                // exact Kafka record that produced it without carrying anything about the
                // patient.
                put("source", "urn:firmus:hl7-ingest:event:${event.eventId}")
            }
            putJsonArray("identifier") {
                addJsonObject {
                    put("use", "usual")
                    codeableConcept("type", IdentifierTypes.MEDICAL_RECORD_NUMBER)
                    put("system", systemUri)
                    put("value", patient.medicalRecordNumber)
                }
                for (other in patient.otherIdentifiers.filter { it.isNotBlank() }) {
                    addJsonObject {
                        put("use", "secondary")
                        codeableConcept("type", IdentifierTypes.PATIENT_INTERNAL)
                        put("system", systemUri)
                        put("value", other)
                    }
                }
            }
            if (hasName) {
                putJsonArray("name") {
                    addJsonObject {
                        // `official` is the registered name on the patient index. HL7 PID-5
                        // without a name-type code is the legal name by convention.
                        put("use", "official")
                        if (patient.familyName.isNotBlank()) put("family", patient.familyName.trim())
                        if (given.isNotEmpty()) {
                            putJsonArray("given") { given.forEach { add(it) } }
                        }
                        put("text", (patient.givenName.trim() + " " + patient.familyName.trim()).trim())
                    }
                }
            }
            if (gender != null) put("gender", gender)
            if (birthDate != null) put("birthDate", birthDate)
            // No `active`. An admission message is evidence that a patient record exists, not
            // evidence about whether the record is active — and `active: false` has real
            // consequences for whether a downstream system will let anyone chart against it.
        }
    }

    private fun buildEncounterResource(
        event: AdmissionEvent,
        encounterId: String,
        patientId: String,
        systemUri: String,
        recordedAt: Instant,
    ): JsonObject {
        val encounter = event.encounter
        val period = toFhirDateTime(encounter.admitDateTime, facilityZone)
        val locationDisplay = listOf(encounter.facility, encounter.pointOfCare, encounter.room, encounter.bed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")

        return buildJsonObject {
            put("resourceType", "Encounter")
            put("id", encounterId)
            putJsonObject("meta") {
                put("lastUpdated", toFhirInstant(recordedAt))
                put("source", "urn:firmus:hl7-ingest:event:${event.eventId}")
            }
            if (encounter.visitNumber.isNotBlank()) {
                putJsonArray("identifier") {
                    addJsonObject {
                        put("use", "official")
                        codeableConcept("type", IdentifierTypes.VISIT_NUMBER)
                        put("system", systemUri)
                        put("value", encounter.visitNumber)
                    }
                }
            }
            put("status", mapEncounterStatus(event.triggerEvent()))
            putJsonObject("class") { coding(mapEncounterClass(encounter.patientClass)) }
            putJsonObject("subject") {
                put("reference", "Patient/$patientId")
                put("type", "Patient")
            }
            if (encounter.attendingClinician.isNotBlank()) {
                putJsonArray("participant") {
                    addJsonObject {
                        putJsonArray("type") {
                            addJsonObject {
                                putJsonArray("coding") {
                                    addJsonObject {
                                        put("system", FhirSystems.V3_PARTICIPATION_TYPE)
                                        put("code", "ATND")
                                        put("display", "attender")
                                    }
                                }
                            }
                        }
                        // A display-only reference, because the ADT gives a name and no
                        // Practitioner resource exists to point at. FHIR permits this precisely
                        // so a mapping does not have to fabricate a Practitioner it cannot
                        // identify or de-duplicate.
                        putJsonObject("individual") { put("display", encounter.attendingClinician.trim()) }
                    }
                }
            }
            if (period != null) {
                putJsonObject("period") { put("start", period) }
            }
            if (locationDisplay.isNotEmpty()) {
                putJsonArray("location") {
                    addJsonObject {
                        putJsonObject("location") { put("display", locationDisplay) }
                        put("status", "active")
                    }
                }
            }
            if (encounter.facility.isNotBlank()) {
                putJsonObject("serviceProvider") { put("display", encounter.facility.trim()) }
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val UNSAFE_URI_CHARS = Regex("[^A-Za-z0-9._~-]")

        /**
         * HL7 v2 table 0001 (administrative sex) to the FHIR `administrative-gender` value set.
         *
         * | HL7 | FHIR | |
         * |---|---|---|
         * | `M` | `male` | |
         * | `F` | `female` | |
         * | `O` — other | `other` | |
         * | `A` — ambiguous | `other` | a recorded finding, just not male or female |
         * | `N` — not applicable | `unknown` | see below |
         * | `U` — unknown | `unknown` | |
         * | absent | *element omitted* | the message did not say |
         * | anything else | `unknown` | a code we cannot read is not evidence of a gender |
         *
         * The R4 ConceptMap for this table maps `N` onto `other`. This service maps it onto
         * `unknown` instead, deliberately: `other` reads downstream as "a gender was recorded
         * and it was neither male nor female", which is a clinical assertion, whereas HL7's `N`
         * means the concept does not apply to this record at all. `unknown` is the weaker and
         * therefore safer of the two. The deviation is documented in the README so it is a
         * decision rather than a bug.
         */
        fun mapGender(hl7AdministrativeSex: String): String? =
            when (hl7AdministrativeSex.trim().uppercase()) {
                "" -> null
                "M" -> "male"
                "F" -> "female"
                "O", "A" -> "other"
                else -> "unknown"
            }

        /**
         * HL7 v2 table 0004 (patient class) to a v3 ActCode encounter class.
         *
         * `B` (obstetrics) and `R` (recurring) are folded onto the inpatient and ambulatory
         * codes they behave as; neither has a distinct ActCode, and inventing one would produce
         * a code no terminology server can resolve.
         */
        fun mapEncounterClass(hl7PatientClass: String): Coding =
            when (hl7PatientClass.trim().uppercase()) {
                "I", "B" -> EncounterClasses.INPATIENT
                "O", "R" -> EncounterClasses.AMBULATORY
                "E" -> EncounterClasses.EMERGENCY
                "P" -> EncounterClasses.PRE_ADMISSION
                else -> EncounterClasses.UNKNOWN
            }

        /**
         * The ADT trigger to `Encounter.status`, which is 1..1 in R4.
         *
         * Only the discharge and cancellation triggers say anything definite. Everything else
         * this pipeline accepts — admit, transfer, update, registration — describes an encounter
         * that is open at the moment the message was sent, so `in-progress` is the honest
         * default rather than a guess.
         */
        fun mapEncounterStatus(triggerEvent: String): String =
            when (triggerEvent) {
                "A03" -> "finished"
                "A11", "A38" -> "cancelled"
                else -> "in-progress"
            }
    }
}
