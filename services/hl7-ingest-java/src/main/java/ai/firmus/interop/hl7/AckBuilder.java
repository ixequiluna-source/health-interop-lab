package ai.firmus.interop.hl7;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds HL7 v2 acknowledgements.
 *
 * <p>The acknowledgement is the only thing the sending system sees, and it decides whether the
 * message is retried, parked in an error queue or dropped. Two rules matter:
 *
 * <ul>
 *   <li>MSH-3/4 and MSH-5/6 are <b>swapped</b> relative to the inbound message — the ACK is
 *       addressed back to the sender. Echoing them unchanged makes the ACK look like it came
 *       from the sender's own system.
 *   <li>MSA-2 echoes the inbound MSH-10 verbatim. It is the sender's correlation key; a
 *       freshly generated id there leaves the sender unable to close out the message and it
 *       will resend indefinitely.
 * </ul>
 */
public final class AckBuilder {

    private static final DateTimeFormatter HL7_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** HL7 table 0008 acknowledgement codes. */
    public enum AckCode {
        /** Application accept. */
        AA,
        /** Application error — do not retry unchanged. */
        AE,
        /** Application reject — malformed or unsupported. */
        AR
    }

    private final Clock clock;
    private final String receivingApplication;
    private final String receivingFacility;

    public AckBuilder(String receivingApplication, String receivingFacility, Clock clock) {
        this.receivingApplication = receivingApplication;
        this.receivingFacility = receivingFacility;
        this.clock = clock;
    }

    public String accept(Hl7Message inbound) {
        return build(inbound, AckCode.AA, null);
    }

    public String error(Hl7Message inbound, String reason) {
        return build(inbound, AckCode.AE, reason);
    }

    /**
     * Rejects a payload that could not be parsed at all.
     *
     * <p>There is no inbound message to echo, so MSA-2 carries a placeholder and the reason
     * goes in MSA-3. Silently dropping unparseable payloads is what turns a bad interface into
     * an invisible one.
     */
    public String reject(String reason) {
        String now = HL7_TS.format(clock.instant().atZone(ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|")
                .append(receivingApplication)
                .append('|')
                .append(receivingFacility)
                .append("|UNKNOWN|UNKNOWN|")
                .append(now)
                .append("||ACK|")
                .append("REJ").append(now)
                .append("|P|2.5.1\r");
        sb.append("MSA|").append(AckCode.AR).append("|UNKNOWN|")
                .append(sanitize(reason))
                .append('\r');
        return sb.toString();
    }

    private String build(Hl7Message inbound, AckCode code, String reason) {
        EncodingCharacters enc = inbound.encoding();
        String now = HL7_TS.format(clock.instant().atZone(ZoneOffset.UTC));
        String controlId = inbound.controlId();

        String sendingApp = inbound.getOrEmpty("MSH-3");
        String sendingFacility = inbound.getOrEmpty("MSH-4");
        String processingId = inbound.get("MSH-11").orElse("P");
        String version = inbound.get("MSH-12").orElse("2.5.1");

        StringBuilder sb = new StringBuilder();
        sb.append("MSH")
                .append(enc.field())
                .append(enc.msh2())
                .append(enc.field())
                .append(receivingApplication)
                .append(enc.field())
                .append(receivingFacility)
                .append(enc.field())
                .append(sendingApp)
                .append(enc.field())
                .append(sendingFacility)
                .append(enc.field())
                .append(now)
                .append(enc.field())
                .append(enc.field())
                .append("ACK")
                .append(enc.component())
                .append(inbound.triggerEvent())
                .append(enc.field())
                .append("ACK")
                .append(now)
                .append(enc.field())
                .append(processingId)
                .append(enc.field())
                .append(version)
                .append('\r');

        sb.append("MSA").append(enc.field()).append(code).append(enc.field()).append(controlId);
        if (reason != null && !reason.isBlank()) {
            sb.append(enc.field()).append(Hl7Parser.escape(sanitize(reason), enc));
        }
        sb.append('\r');
        return sb.toString();
    }

    /** Keeps segment terminators out of a free-text reason so the ACK stays well-formed. */
    private static String sanitize(String reason) {
        if (reason == null) {
            return "";
        }
        String cleaned = reason.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }
}
