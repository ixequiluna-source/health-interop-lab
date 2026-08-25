package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestHandlerTest {

    private InMemoryEventSink sink;
    private IngestHandler handler;

    @BeforeEach
    void setUp() {
        sink = new InMemoryEventSink();
        handler = newHandler(sink);
    }

    private static IngestHandler newHandler(EventSink sink) {
        return new IngestHandler(
                new AdtMapper(Fixtures.FIXED_CLOCK),
                new AckBuilder("INTEROP_LAB", "FIRMUS", Fixtures.FIXED_CLOCK),
                sink);
    }

    @Test
    @DisplayName("a valid admit is published once and acknowledged with AA")
    void validMessageIsPublishedAndAccepted() {
        Hl7Message ack = Hl7Parser.parse(handler.handle(Fixtures.ADT_A01));

        assertEquals("AA", ack.getOrEmpty("MSA-1"));
        assertEquals(1, sink.count());
        assertEquals("MRN-88213", sink.published().get(0).patient().medicalRecordNumber());
        assertEquals(1, handler.acceptedCount());
    }

    @Test
    @DisplayName("an unparseable payload gets AR and publishes nothing")
    void unparseablePayloadIsRejected() {
        Hl7Message ack = Hl7Parser.parse(handler.handle("this is not HL7 at all"));

        assertEquals("AR", ack.getOrEmpty("MSA-1"));
        assertEquals(0, sink.count());
        assertEquals(1, handler.rejectedCount());
    }

    @Test
    @DisplayName("a valid message this service will not process gets AE, not AR")
    void unsupportedTriggerIsAnApplicationError() {
        Hl7Message ack = Hl7Parser.parse(handler.handle(Fixtures.ADT_A11));

        assertEquals("AE", ack.getOrEmpty("MSA-1"));
        assertEquals("MSG00001", ack.getOrEmpty("MSA-2"));
        assertEquals(0, sink.count());
    }

    @Test
    @DisplayName("a publish failure withholds the ACK so the sender retries")
    void publishFailureIsRetryable() {
        IngestHandler failing =
                newHandler(
                        event -> {
                            throw new EventSink.EventPublishException(
                                    "broker unavailable", new IllegalStateException());
                        });

        IngestHandler.RetryableFailure error =
                assertThrows(
                        IngestHandler.RetryableFailure.class, () -> failing.handle(Fixtures.ADT_A01));
        assertTrue(error.getMessage().contains("Could not publish"));
        assertEquals(1, failing.failedCount());
        assertEquals(0, failing.acceptedCount());
    }

    @Test
    @DisplayName("each accepted message gets its own event id")
    void eventIdsAreUnique() {
        handler.handle(Fixtures.ADT_A01);
        handler.handle(Fixtures.ADT_A01);

        assertEquals(2, sink.count());
        assertTrue(
                !sink.published().get(0).eventId().equals(sink.published().get(1).eventId()),
                "duplicate event ids would collapse two admissions into one downstream");
    }

    @Test
    @DisplayName("counters separate the three outcomes")
    void countersTrackOutcomes() {
        handler.handle(Fixtures.ADT_A01);
        handler.handle(Fixtures.ADT_A11);
        handler.handle("garbage");

        assertEquals(1, handler.acceptedCount());
        assertEquals(2, handler.rejectedCount());
        assertEquals(0, handler.failedCount());
    }
}
