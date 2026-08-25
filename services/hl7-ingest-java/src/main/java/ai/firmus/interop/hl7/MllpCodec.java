package ai.firmus.interop.hl7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Optional;

/**
 * Minimal Lower Layer Protocol framing, the transport almost every HL7 v2 feed actually uses.
 *
 * <p>A frame is {@code <VT> payload <FS><CR>}. The framing exists because HL7 payloads contain
 * carriage returns as segment terminators, so a bare TCP stream gives the reader no way to
 * know where one message ends. Reading "until CR" — a common shortcut — truncates every
 * message at its first segment.
 */
public final class MllpCodec {

    public static final byte START_BLOCK = 0x0B;
    public static final byte END_BLOCK = 0x1C;
    public static final byte CARRIAGE_RETURN = 0x0D;

    /** Guards against a peer that opens a connection and streams without ever framing. */
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private MllpCodec() {}

    /**
     * Reads one frame, or empty when the peer closed the connection cleanly.
     *
     * @throws Hl7ParseException if the stream is framed incorrectly or the frame is oversized
     */
    public static Optional<String> readFrame(InputStream in, Charset charset) throws IOException {
        int b = in.read();
        while (b != -1 && b != START_BLOCK) {
            // Tolerate keep-alive whitespace between frames; reject anything else.
            if (b != CARRIAGE_RETURN && b != '\n' && b != ' ') {
                throw new Hl7ParseException(
                        "Expected MLLP start block 0x0B, got 0x%02X".formatted(b));
            }
            b = in.read();
        }
        if (b == -1) {
            return Optional.empty();
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream(4096);
        while (true) {
            int next = in.read();
            if (next == -1) {
                throw new Hl7ParseException("Stream ended inside an MLLP frame");
            }
            if (next == END_BLOCK) {
                int cr = in.read();
                if (cr != CARRIAGE_RETURN) {
                    throw new Hl7ParseException(
                            "MLLP end block must be followed by 0x0D, got 0x%02X".formatted(cr));
                }
                return Optional.of(payload.toString(charset));
            }
            if (payload.size() >= MAX_FRAME_BYTES) {
                throw new Hl7ParseException("MLLP frame exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            payload.write(next);
        }
    }

    /** Writes one framed message and flushes, so the sender is not left waiting on a buffer. */
    public static void writeFrame(OutputStream out, String message, Charset charset)
            throws IOException {
        out.write(START_BLOCK);
        out.write(message.getBytes(charset));
        out.write(END_BLOCK);
        out.write(CARRIAGE_RETURN);
        out.flush();
    }

    /** Wraps a message in MLLP framing, for tests and for feeding a decoder. */
    public static byte[] frame(String message, Charset charset) {
        byte[] body = message.getBytes(charset);
        byte[] framed = new byte[body.length + 3];
        framed[0] = START_BLOCK;
        System.arraycopy(body, 0, framed, 1, body.length);
        framed[body.length + 1] = END_BLOCK;
        framed[body.length + 2] = CARRIAGE_RETURN;
        return framed;
    }
}
