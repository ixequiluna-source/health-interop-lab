package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AckBuilderTest {

    private final AckBuilder builder =
            new AckBuilder("INTEROP_LAB", "FIRMUS", Fixtures.FIXED_CLOCK);

    private static Hl7Message ack(String raw) {
        return Hl7Parser.parse(raw);
    }

    @Test
    @DisplayName("an accept carries AA and echoes the sender's control id in MSA-2")
    void acceptEchoesControlId() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01);
        Hl7Message response = ack(builder.accept(inbound));

        assertEquals("ACK", response.getOrEmpty("MSH-9-1"));
        assertEquals("AA", response.getOrEmpty("MSA-1"));
        assertEquals("MSG00001", response.getOrEmpty("MSA-2"));
    }

    @Test
    @DisplayName("sender and receiver are swapped so the ACK is addressed back to the sender")
    void swapsSenderAndReceiver() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01);
        Hl7Message response = ack(builder.accept(inbound));

        assertEquals("INTEROP_LAB", response.getOrEmpty("MSH-3"));
        assertEquals("FIRMUS", response.getOrEmpty("MSH-4"));
        assertEquals("EPIC_ADT", response.getOrEmpty("MSH-5"));
        assertEquals("HGS_PUEBLA", response.getOrEmpty("MSH-6"));
    }

    @Test
    @DisplayName("the ACK mirrors the inbound processing id and version")
    void mirrorsProcessingIdAndVersion() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01);
        Hl7Message response = ack(builder.accept(inbound));

        assertEquals("P", response.getOrEmpty("MSH-11"));
        assertEquals("2.5.1", response.getOrEmpty("MSH-12"));
        assertEquals("A01", response.getOrEmpty("MSH-9-2"));
    }

    @Test
    void errorCarriesAeAndTheReason() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A11);
        Hl7Message response = ack(builder.error(inbound, "Unsupported ADT trigger event 'A11'"));

        assertEquals("AE", response.getOrEmpty("MSA-1"));
        assertEquals("MSG00001", response.getOrEmpty("MSA-2"));
        assertTrue(response.getOrEmpty("MSA-3").contains("A11"));
    }

    @Test
    @DisplayName("an unparseable payload still gets a well-formed AR rather than silence")
    void rejectIsWellFormed() {
        Hl7Message response = ack(builder.reject("Message is too short to contain an MSH header"));

        assertEquals("AR", response.getOrEmpty("MSA-1"));
        assertEquals("ACK", response.getOrEmpty("MSH-9-1"));
        assertEquals("INTEROP_LAB", response.getOrEmpty("MSH-3"));
        assertTrue(response.getOrEmpty("MSA-3").contains("MSH"));
    }

    @Test
    @DisplayName("a reason containing a carriage return cannot forge extra segments")
    void reasonCannotInjectSegments() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01);
        String raw = builder.error(inbound, "bad\rPID|1||INJECTED");

        Hl7Message response = ack(raw);
        assertTrue(response.segment("PID").isEmpty());
        assertEquals(2, response.segments().size());
    }

    @Test
    @DisplayName("delimiters inside the reason are escaped, keeping MSA-3 a single field")
    void reasonDelimitersAreEscaped() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01);
        Hl7Message response = ack(builder.error(inbound, "value|with^delimiters"));

        assertEquals("value|with^delimiters", response.getOrEmpty("MSA-3"));
        assertEquals(3, response.segment("MSA").orElseThrow().fieldCount());
    }

    @Test
    void ackUsesTheInboundDelimiters() {
        Hl7Message inbound = Hl7Parser.parse(Fixtures.ADT_A01_CUSTOM_DELIMITERS);
        String raw = builder.accept(inbound);

        assertTrue(raw.startsWith("MSH#@~\\&#"));
        Hl7Message response = ack(raw);
        assertEquals("AA", response.getOrEmpty("MSA-1"));
        assertEquals("MSG00002", response.getOrEmpty("MSA-2"));
    }

    @Test
    void ackHasNoTrailingGarbage() {
        String raw = builder.accept(Hl7Parser.parse(Fixtures.ADT_A01));
        assertTrue(raw.endsWith("\r"));
        assertFalse(raw.contains("\n"));
    }
}
