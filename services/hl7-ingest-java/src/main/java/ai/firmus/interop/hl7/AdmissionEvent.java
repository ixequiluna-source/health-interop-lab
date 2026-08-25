package ai.firmus.interop.hl7;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical admission event published onto Kafka.
 *
 * <p>This envelope — not raw HL7 — is the contract every downstream consumer sees. Publishing
 * the pipe-delimited original instead would force each consumer to re-implement HL7 parsing,
 * and they would each get the MSH offset and the escape sequences subtly wrong in their own
 * way. One parser, one shape, versioned.
 */
public record AdmissionEvent(
        String schemaVersion,
        String eventId,
        String messageControlId,
        String messageType,
        String sendingApplication,
        String sendingFacility,
        String recordedAt,
        Patient patient,
        Encounter encounter) {

    public static final String SCHEMA_VERSION = "1.0.0";

    public record Patient(
            String medicalRecordNumber,
            List<String> otherIdentifiers,
            String familyName,
            String givenName,
            String birthDate,
            String administrativeSex) {

        public String toJson() {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("medicalRecordNumber", Json.optional(medicalRecordNumber));
            f.put(
                    "otherIdentifiers",
                    otherIdentifiers.isEmpty() ? null : Json.stringArray(otherIdentifiers));
            f.put("familyName", Json.optional(familyName));
            f.put("givenName", Json.optional(givenName));
            f.put("birthDate", Json.optional(birthDate));
            f.put("administrativeSex", Json.optional(administrativeSex));
            return Json.object(f);
        }
    }

    public record Encounter(
            String visitNumber,
            String patientClass,
            String admitDateTime,
            String attendingClinician,
            String pointOfCare,
            String room,
            String bed,
            String facility) {

        public String toJson() {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("visitNumber", Json.optional(visitNumber));
            f.put("patientClass", Json.optional(patientClass));
            f.put("admitDateTime", Json.optional(admitDateTime));
            f.put("attendingClinician", Json.optional(attendingClinician));
            f.put("pointOfCare", Json.optional(pointOfCare));
            f.put("room", Json.optional(room));
            f.put("bed", Json.optional(bed));
            f.put("facility", Json.optional(facility));
            return Json.object(f);
        }
    }

    /**
     * The Kafka partition key.
     *
     * <p>Keying by MRN keeps every event for one patient on one partition, which is what makes
     * ordering meaningful: an A01 admit followed by an A08 update must not be reordered into
     * an update against a patient who has not been admitted yet.
     */
    public String partitionKey() {
        String mrn = patient.medicalRecordNumber();
        return mrn == null || mrn.isEmpty() ? eventId : mrn;
    }

    public String toJson() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("schemaVersion", Json.string(schemaVersion));
        f.put("eventId", Json.string(eventId));
        f.put("messageControlId", Json.optional(messageControlId));
        f.put("messageType", Json.optional(messageType));
        f.put("sendingApplication", Json.optional(sendingApplication));
        f.put("sendingFacility", Json.optional(sendingFacility));
        f.put("recordedAt", Json.string(recordedAt));
        f.put("patient", patient.toJson());
        f.put("encounter", encounter.toJson());
        return Json.object(f);
    }
}
