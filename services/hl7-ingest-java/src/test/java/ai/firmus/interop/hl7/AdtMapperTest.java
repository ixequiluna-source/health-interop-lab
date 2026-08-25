package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdtMapperTest {

    private final AdtMapper mapper = new AdtMapper(Fixtures.FIXED_CLOCK);

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void mapsPatientDemographics() {
            AdmissionEvent event = mapper.map(Hl7Parser.parse(Fixtures.ADT_A01));
            AdmissionEvent.Patient p = event.patient();
            assertEquals("MRN-88213", p.medicalRecordNumber());
            assertEquals("Luna", p.familyName());
            assertEquals("Ixequi", p.givenName());
            assertEquals("1990-03-14", p.birthDate());
            assertEquals("M", p.administrativeSex());
        }

        @Test
        @DisplayName("secondary identifiers are carried, not discarded")
        void keepsSecondaryIdentifiers() {
            AdmissionEvent event = mapper.map(Hl7Parser.parse(Fixtures.ADT_A01));
            assertEquals(List.of("NSS-4471120"), event.patient().otherIdentifiers());
        }

        @Test
        void mapsEncounter() {
            AdmissionEvent event = mapper.map(Hl7Parser.parse(Fixtures.ADT_A01));
            AdmissionEvent.Encounter e = event.encounter();
            assertEquals("VN-556677", e.visitNumber());
            assertEquals("I", e.patientClass());
            assertEquals("2026-08-25T14:30:00", e.admitDateTime());
            assertEquals("Enrique Torres", e.attendingClinician());
            assertEquals("WARD-3", e.pointOfCare());
            assertEquals("301", e.room());
            assertEquals("A", e.bed());
            assertEquals("HGS_PUEBLA", e.facility());
        }

        @Test
        void carriesMessageProvenance() {
            AdmissionEvent event = mapper.map(Hl7Parser.parse(Fixtures.ADT_A01));
            assertEquals("MSG00001", event.messageControlId());
            assertEquals("ADT^A01", event.messageType());
            assertEquals("EPIC_ADT", event.sendingApplication());
            assertEquals("HGS_PUEBLA", event.sendingFacility());
            assertEquals("2026-08-25T14:30:00Z", event.recordedAt());
            assertEquals(AdmissionEvent.SCHEMA_VERSION, event.schemaVersion());
        }

        @Test
        @DisplayName("the partition key is the MRN, so one patient stays on one partition")
        void partitionsByMedicalRecordNumber() {
            AdmissionEvent event = mapper.map(Hl7Parser.parse(Fixtures.ADT_A01));
            assertEquals("MRN-88213", event.partitionKey());
        }

        @ParameterizedTest(name = "trigger {0} is accepted")
        @ValueSource(strings = {"A01", "A02", "A03", "A04", "A08", "A28", "A31"})
        void acceptsEveryDocumentedTrigger(String trigger) {
            String raw = Fixtures.ADT_A01.replace("ADT^A01", "ADT^" + trigger);
            assertEquals("ADT^" + trigger, mapper.map(Hl7Parser.parse(raw)).messageType());
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("a non-ADT message is refused rather than mapped as an admission")
        void refusesNonAdt() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ORU_R01);
            Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> mapper.map(m));
            assertTrue(e.getMessage().contains("expected ADT"));
        }

        @Test
        @DisplayName("an unsupported trigger is refused instead of silently creating a visit")
        void refusesUnsupportedTrigger() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A11);
            Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> mapper.map(m));
            assertTrue(e.getMessage().contains("A11"));
        }

        @Test
        void refusesMessageWithoutPid() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01_NO_PID);
            assertThrows(Hl7ParseException.class, () -> mapper.map(m));
        }

        @Test
        @DisplayName("a patient with no identifier is refused: there is nothing to reconcile on")
        void refusesMessageWithoutIdentifier() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01_NO_IDENTIFIER);
            assertThrows(Hl7ParseException.class, () -> mapper.map(m));
        }
    }

    @Nested
    @DisplayName("Date normalisation")
    class Dates {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "19900314, 1990-03-14",
            "199003, 1990-03",
            "1990, 1990",
            "19900314153000, 1990-03-14"
        })
        @DisplayName("partial dates are widened, never padded into an invented birthday")
        void normalisesDates(String input, String expected) {
            assertEquals(expected, AdtMapper.normaliseDate(input));
        }

        @ParameterizedTest
        @CsvSource({"'', ''", "'abcd', ''", "'199', ''", "'19900', ''"})
        void unusableDatesBecomeEmpty(String input, String expected) {
            assertEquals(expected, AdtMapper.normaliseDate(input));
        }
    }

    @Nested
    @DisplayName("Timestamp normalisation")
    class Timestamps {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "20260825143000, 2026-08-25T14:30:00",
            "202608251430, 2026-08-25T14:30:00",
            "20260825, 2026-08-25",
            "20260825143000.500, 2026-08-25T14:30:00"
        })
        void normalisesTimestamps(String input, String expected) {
            assertEquals(expected, AdtMapper.normaliseTimestamp(input));
        }

        @Test
        @DisplayName("a sender-supplied offset is preserved, not rewritten to UTC")
        void preservesOffset() {
            assertEquals(
                    "2026-08-25T14:30:00-06:00",
                    AdtMapper.normaliseTimestamp("20260825143000-0600"));
        }

        @Test
        @DisplayName("an absent offset stays absent rather than being assumed to be UTC")
        void doesNotInventAnOffset() {
            assertEquals("2026-08-25T14:30:00", AdtMapper.normaliseTimestamp("20260825143000"));
        }

        @ParameterizedTest
        @CsvSource({"'', ''", "'notadate', ''", "'2026', ''"})
        void unusableTimestampsBecomeEmpty(String input, String expected) {
            assertEquals(expected, AdtMapper.normaliseTimestamp(input));
        }
    }
}
