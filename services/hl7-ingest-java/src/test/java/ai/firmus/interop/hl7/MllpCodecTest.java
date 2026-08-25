package ai.firmus.interop.hl7;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MllpCodecTest {

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    void framesRoundTrip() throws IOException {
        byte[] framed = MllpCodec.frame(Fixtures.ADT_A01, UTF_8);
        assertEquals(Optional.of(Fixtures.ADT_A01), MllpCodec.readFrame(stream(framed), UTF_8));
    }

    @Test
    @DisplayName("a message containing carriage returns is not truncated at the first segment")
    void doesNotTruncateAtCarriageReturn() throws IOException {
        byte[] framed = MllpCodec.frame(Fixtures.ADT_A01, UTF_8);
        String decoded = MllpCodec.readFrame(stream(framed), UTF_8).orElseThrow();
        assertTrue(decoded.contains("PID|"));
        assertTrue(decoded.contains("PV1|"));
        assertEquals(4, decoded.split("\r").length);
    }

    @Test
    @DisplayName("consecutive frames on one long-lived connection are read in order")
    void readsConsecutiveFrames() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(MllpCodec.frame("MSH|^~\\&|first", UTF_8));
        buffer.writeBytes(MllpCodec.frame("MSH|^~\\&|second", UTF_8));

        InputStream in = stream(buffer.toByteArray());
        assertEquals(Optional.of("MSH|^~\\&|first"), MllpCodec.readFrame(in, UTF_8));
        assertEquals(Optional.of("MSH|^~\\&|second"), MllpCodec.readFrame(in, UTF_8));
        assertEquals(Optional.empty(), MllpCodec.readFrame(in, UTF_8));
    }

    @Test
    @DisplayName("a clean close between frames reports end of stream, not an error")
    void cleanCloseIsNotAnError() throws IOException {
        assertEquals(Optional.empty(), MllpCodec.readFrame(stream(new byte[0]), UTF_8));
    }

    @Test
    void unframedPayloadIsRejected() {
        byte[] raw = "MSH|^~\\&|no framing".getBytes(UTF_8);
        assertThrows(Hl7ParseException.class, () -> MllpCodec.readFrame(stream(raw), UTF_8));
    }

    @Test
    @DisplayName("a truncated frame is an error rather than a silently short message")
    void truncatedFrameIsRejected() {
        byte[] partial = {MllpCodec.START_BLOCK, 'M', 'S', 'H'};
        assertThrows(Hl7ParseException.class, () -> MllpCodec.readFrame(stream(partial), UTF_8));
    }

    @Test
    void endBlockWithoutCarriageReturnIsRejected() {
        byte[] bad = {MllpCodec.START_BLOCK, 'M', MllpCodec.END_BLOCK, 'X'};
        assertThrows(Hl7ParseException.class, () -> MllpCodec.readFrame(stream(bad), UTF_8));
    }

    @Test
    @DisplayName("inter-frame whitespace from chatty senders is tolerated")
    void toleratesInterFrameWhitespace() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write('\r');
        buffer.write('\n');
        buffer.writeBytes(MllpCodec.frame("MSH|^~\\&|ok", UTF_8));
        assertEquals(Optional.of("MSH|^~\\&|ok"), MllpCodec.readFrame(stream(buffer.toByteArray()), UTF_8));
    }

    @Test
    void writeFrameProducesTheExpectedBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MllpCodec.writeFrame(out, "AB", UTF_8);
        assertArrayEquals(
                new byte[] {
                    MllpCodec.START_BLOCK, 'A', 'B', MllpCodec.END_BLOCK, MllpCodec.CARRIAGE_RETURN
                },
                out.toByteArray());
    }

    @Test
    @DisplayName("non-ASCII patient names survive the byte round trip")
    void preservesUtf8() throws IOException {
        String message = "MSH|^~\\&|A\rPID|1||X||Núñez^José";
        byte[] framed = MllpCodec.frame(message, UTF_8);
        assertEquals(Optional.of(message), MllpCodec.readFrame(stream(framed), UTF_8));
    }
}
