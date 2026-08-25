package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapesQuotesAndBackslashes() {
        assertEquals("\"a\\\"b\\\\c\"", Json.string("a\"b\\c"));
    }

    @Test
    @DisplayName("control characters below 0x20 are escaped as \\u sequences")
    void escapesControlCharacters() {
        assertEquals("\"\\u0001\"", Json.string("\u0001"));
        assertEquals("\"\\n\"", Json.string("\n"));
        assertEquals("\"\\r\"", Json.string("\r"));
        assertEquals("\"\\t\"", Json.string("\t"));
        assertEquals("\"\\b\"", Json.string("\b"));
        assertEquals("\"\\f\"", Json.string("\f"));
    }

    @Test
    void nullBecomesJsonNull() {
        assertEquals("null", Json.string(null));
    }

    @Test
    @DisplayName("absent fields are omitted rather than rendered as null")
    void omitsNullValues() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("present", Json.string("yes"));
        fields.put("absent", Json.optional(""));
        assertEquals("{\"present\":\"yes\"}", Json.object(fields));
    }

    @Test
    void encodesStringArrays() {
        assertEquals("[\"a\",\"b\"]", Json.stringArray(List.of("a", "b")));
        assertEquals("[]", Json.stringArray(List.of()));
    }

    @Test
    @DisplayName("a name containing a quote cannot break out of the event envelope")
    void eventEnvelopeIsInjectionSafe() {
        AdmissionEvent event =
                new AdtMapper(Fixtures.FIXED_CLOCK)
                        .map(
                                Hl7Parser.parse(
                                        Fixtures.ADT_A01.replace(
                                                "Luna^Ixequi^M", "Lu\\S\\na\"^Ixequi^M")));
        String json = event.toJson();

        assertTrue(json.contains("\\\""), "the quote must be escaped");
        assertFalse(json.contains("na\"^"), "raw quote must not survive into the envelope");
        // Root, patient and encounter: a quote that escaped its string would open more.
        assertEquals(3, json.chars().filter(c -> c == '{').count());
    }

    @Test
    void eventJsonCarriesTheExpectedShape() {
        AdmissionEvent event =
                new AdtMapper(Fixtures.FIXED_CLOCK).map(Hl7Parser.parse(Fixtures.ADT_A01));
        String json = event.toJson();

        assertTrue(json.startsWith("{\"schemaVersion\":\"1.0.0\""));
        assertTrue(json.contains("\"medicalRecordNumber\":\"MRN-88213\""));
        assertTrue(json.contains("\"otherIdentifiers\":[\"NSS-4471120\"]"));
        assertTrue(json.contains("\"visitNumber\":\"VN-556677\""));
        assertTrue(json.contains("\"recordedAt\":\"2026-08-25T14:30:00Z\""));
    }
}
