package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Hl7ParserTest {

    @Nested
    @DisplayName("MSH header handling")
    class MshHandling {

        @Test
        @DisplayName("MSH-1 is the field separator itself, not the first data token")
        void mshOneIsTheFieldSeparator() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals("|", m.getOrEmpty("MSH-1"));
        }

        @Test
        @DisplayName("MSH-2 keeps the delimiter characters verbatim instead of being split")
        void mshTwoIsNotSplit() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals("^~\\&", m.getOrEmpty("MSH-2"));
        }

        @Test
        @DisplayName("data fields keep the numbering the sender used")
        void dataFieldsAreNotOffByOne() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals("EPIC_ADT", m.getOrEmpty("MSH-3"));
            assertEquals("HGS_PUEBLA", m.getOrEmpty("MSH-4"));
            assertEquals("INTEROP_LAB", m.getOrEmpty("MSH-5"));
            assertEquals("FIRMUS", m.getOrEmpty("MSH-6"));
            assertEquals("20260825143000", m.getOrEmpty("MSH-7"));
            assertEquals("ADT", m.getOrEmpty("MSH-9-1"));
            assertEquals("A01", m.getOrEmpty("MSH-9-2"));
            assertEquals("MSG00001", m.getOrEmpty("MSH-10"));
            assertEquals("P", m.getOrEmpty("MSH-11"));
            assertEquals("2.5.1", m.getOrEmpty("MSH-12"));
        }

        @Test
        @DisplayName("MSH-8 is empty in the fixture and stays empty rather than shifting")
        void emptyFieldDoesNotShiftLaterFields() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertTrue(m.get("MSH-8").isEmpty());
            assertEquals("ADT^A01", m.messageType());
        }
    }

    @Nested
    @DisplayName("Structure below field level")
    class Structure {

        @Test
        @DisplayName("repeating PID-3 identifiers are all preserved")
        void repetitionsArePreserved() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals(List.of("MRN-88213", "NSS-4471120"), m.getRepetitions("PID", 3, 1));
        }

        @Test
        @DisplayName("each repetition keeps its own assigning authority and id type")
        void repetitionComponentsAreAddressable() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals("HGS", m.getOrEmpty("PID-3(1)-4"));
            assertEquals("MR", m.getOrEmpty("PID-3(1)-5"));
            assertEquals("IMSS", m.getOrEmpty("PID-3(2)-4"));
            assertEquals("SS", m.getOrEmpty("PID-3(2)-5"));
        }

        @Test
        @DisplayName("components of a compound location are addressable")
        void componentsAreAddressable() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertEquals("WARD-3", m.getOrEmpty("PV1-3-1"));
            assertEquals("301", m.getOrEmpty("PV1-3-2"));
            assertEquals("A", m.getOrEmpty("PV1-3-3"));
            assertEquals("HGS_PUEBLA", m.getOrEmpty("PV1-3-4"));
        }

        @Test
        @DisplayName("sub-components split on the sub-component separator")
        void subComponentsAreAddressable() {
            Hl7Message m =
                    Hl7Parser.parse(
                            "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||X^^^AUTH&UNIVERSAL&ISO");
            assertEquals("AUTH", m.getOrEmpty("PID-3-4-1"));
            assertEquals("UNIVERSAL", m.getOrEmpty("PID-3-4-2"));
            assertEquals("ISO", m.getOrEmpty("PID-3-4-3"));
        }

        @Test
        @DisplayName("trailing empty fields are kept, so 'sent empty' stays distinguishable")
        void trailingEmptyFieldsAreKept() {
            Hl7Message m =
                    Hl7Parser.parse("MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||||");
            Segment pid = m.segment("PID").orElseThrow();
            assertEquals(5, pid.fieldCount());
        }

        @Test
        @DisplayName("repeated segments are all retained in message order")
        void repeatedSegmentsAreRetained() {
            Hl7Message m =
                    Hl7Parser.parse(
                            "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rNTE|1||first\rNTE|2||second");
            assertEquals(2, m.allSegments("NTE").size());
            assertEquals("second", m.allSegments("NTE").get(1).field(3).orElseThrow()
                    .repetition(1).orElseThrow().component(1).orElseThrow().value());
        }
    }

    @Nested
    @DisplayName("Escape sequences")
    class Escapes {

        @ParameterizedTest(name = "{0} decodes to {1}")
        @CsvSource({
            "'Smith \\F\\ Jones', 'Smith | Jones'",
            "'A \\S\\ B', 'A ^ B'",
            "'A \\T\\ B', 'A & B'",
            "'A \\R\\ B', 'A ~ B'",
            "'A \\E\\ B', 'A \\ B'"
        })
        void delimiterEscapesAreDecoded(String encoded, String expected) {
            String raw =
                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||X||"
                            + encoded;
            assertEquals(expected, Hl7Parser.parse(raw).getOrEmpty("PID-5-1"));
        }

        @Test
        @DisplayName("hexadecimal escapes decode to their characters")
        void hexEscapeIsDecoded() {
            String raw =
                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||X||Nu\\X0F1A\\ez";
            // 0x0F is a control character; the point is that the pair is consumed as hex.
            String value = Hl7Parser.parse(raw).getOrEmpty("PID-5-1");
            assertTrue(value.startsWith("Nu"));
            assertTrue(value.endsWith("ez"));
            assertFalse(value.contains("X0F"));
        }

        @Test
        @DisplayName("formatting escapes become line breaks")
        void formattingEscapeBecomesNewline() {
            String raw =
                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rNTE|1||line one\\.br\\line two";
            Hl7Message m = Hl7Parser.parse(raw);
            String note =
                    m.segment("NTE").orElseThrow().field(3).orElseThrow()
                            .repetition(1).orElseThrow().component(1).orElseThrow().value();
            assertEquals("line one\nline two", note);
        }

        @Test
        @DisplayName("unknown local escapes survive instead of being silently dropped")
        void unknownEscapeIsPreserved() {
            String raw =
                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||X||\\Zlocal\\";
            assertEquals("\\Zlocal\\", Hl7Parser.parse(raw).getOrEmpty("PID-5-1"));
        }

        @Test
        @DisplayName("escaping then parsing round-trips a value containing every delimiter")
        void escapeRoundTrips() {
            String nasty = "a|b^c~d\\e&f";
            String encoded = Hl7Parser.escape(nasty, EncodingCharacters.DEFAULT);
            String raw =
                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPID|1||X||" + encoded;
            assertEquals(nasty, Hl7Parser.parse(raw).getOrEmpty("PID-5-1"));
        }
    }

    @Nested
    @DisplayName("Delimiters and terminators")
    class DelimitersAndTerminators {

        @Test
        @DisplayName("non-default delimiters declared in MSH-2 are honoured")
        void customDelimitersAreHonoured() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01_CUSTOM_DELIMITERS);
            assertEquals('#', m.encoding().field());
            assertEquals('@', m.encoding().component());
            assertEquals("EPIC_ADT", m.getOrEmpty("MSH-3"));
            assertEquals("ADT", m.getOrEmpty("MSH-9-1"));
            assertEquals("A01", m.getOrEmpty("MSH-9-2"));
            assertEquals("Luna", m.getOrEmpty("PID-5-1"));
            assertEquals("Ixequi", m.getOrEmpty("PID-5-2"));
            assertEquals("301", m.getOrEmpty("PV1-3-2"));
        }

        @Test
        @DisplayName("LF-terminated messages parse identically to CR-terminated ones")
        void lineFeedTerminatorsAreAccepted() {
            assertEquals(
                    Hl7Parser.parse(Fixtures.ADT_A01).getOrEmpty("PID-5-1"),
                    Hl7Parser.parse(Fixtures.ADT_A01_LF).getOrEmpty("PID-5-1"));
        }

        @Test
        @DisplayName("CRLF terminators do not produce phantom empty segments")
        void crlfDoesNotCreateEmptySegments() {
            String crlf = Fixtures.ADT_A01.replace("\r", "\r\n");
            assertEquals(
                    Hl7Parser.parse(Fixtures.ADT_A01).segments().size(),
                    Hl7Parser.parse(crlf).segments().size());
        }

        @Test
        @DisplayName("a duplicated delimiter in MSH-2 is rejected")
        void duplicateDelimitersAreRejected() {
            assertThrows(
                    Hl7ParseException.class,
                    () -> Hl7Parser.parse("MSH|^^~&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1"));
        }
    }

    @Nested
    @DisplayName("Malformed input")
    class Malformed {

        @Test
        void emptyPayloadIsRejected() {
            assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(""));
            assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(null));
        }

        @Test
        void payloadNotStartingWithMshIsRejected() {
            assertThrows(
                    Hl7ParseException.class,
                    () -> Hl7Parser.parse("PID|1||MRN-1\rMSH|^~\\&|A|B|C|D|1||ADT^A01|1|P|2.5.1"));
        }

        @Test
        void truncatedHeaderIsRejected() {
            assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse("MSH|^"));
        }

        @Test
        void segmentNameOfWrongLengthIsRejected() {
            assertThrows(
                    Hl7ParseException.class,
                    () ->
                            Hl7Parser.parse(
                                    "MSH|^~\\&|A|B|C|D|20260101000000||ADT^A01|1|P|2.5.1\rPIDX|1"));
        }

        @Test
        void malformedPathIsProgrammerError() {
            Hl7Message m = Hl7Parser.parse(Fixtures.ADT_A01);
            assertThrows(IllegalArgumentException.class, () -> m.get("PID5"));
        }
    }
}
