package ai.firmus.interop.hl7;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Maps an ADT message onto the canonical {@link AdmissionEvent}.
 *
 * <p>The mapper is deliberately strict about which trigger events it accepts. An interface that
 * accepts every ADT trigger and maps them all onto "admission" produces encounters for
 * cancellations and discharges, and the error only surfaces months later as a bed-occupancy
 * report nobody can reconcile.
 */
public final class AdtMapper {

    /** Triggers that create or update an inpatient/outpatient encounter. */
    public static final Set<String> SUPPORTED_TRIGGERS =
            Set.of("A01", "A02", "A03", "A04", "A08", "A28", "A31");

    private static final DateTimeFormatter ISO_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final Clock clock;

    public AdtMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * @throws Hl7ParseException when the message is not a supported ADT or lacks a patient id
     */
    public AdmissionEvent map(Hl7Message message) {
        if (!"ADT".equals(message.messageCode())) {
            throw new Hl7ParseException(
                    "Unsupported message type '" + message.messageType() + "'; expected ADT");
        }
        String trigger = message.triggerEvent();
        if (!SUPPORTED_TRIGGERS.contains(trigger)) {
            throw new Hl7ParseException("Unsupported ADT trigger event '" + trigger + "'");
        }
        if (message.segment("PID").isEmpty()) {
            throw new Hl7ParseException("ADT message has no PID segment");
        }

        List<String> identifiers = message.getRepetitions("PID", 3, 1);
        if (identifiers.isEmpty()) {
            throw new Hl7ParseException("PID-3 carries no patient identifier");
        }
        String mrn = identifiers.get(0);
        List<String> others = identifiers.subList(1, identifiers.size());

        AdmissionEvent.Patient patient =
                new AdmissionEvent.Patient(
                        mrn,
                        List.copyOf(others),
                        message.getOrEmpty("PID-5-1"),
                        message.getOrEmpty("PID-5-2"),
                        normaliseDate(message.getOrEmpty("PID-7")),
                        message.getOrEmpty("PID-8"));

        AdmissionEvent.Encounter encounter =
                new AdmissionEvent.Encounter(
                        message.getOrEmpty("PV1-19"),
                        message.getOrEmpty("PV1-2"),
                        normaliseTimestamp(message.getOrEmpty("PV1-44")),
                        clinicianName(message),
                        message.getOrEmpty("PV1-3-1"),
                        message.getOrEmpty("PV1-3-2"),
                        message.getOrEmpty("PV1-3-3"),
                        message.getOrEmpty("PV1-3-4"));

        return new AdmissionEvent(
                AdmissionEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                message.controlId(),
                message.messageType(),
                message.getOrEmpty("MSH-3"),
                message.getOrEmpty("MSH-4"),
                ISO_INSTANT.format(clock.instant().atZone(ZoneOffset.UTC)),
                patient,
                encounter);
    }

    /** XCN: id ^ family ^ given — rendered as "Given Family" with whatever parts exist. */
    private static String clinicianName(Hl7Message message) {
        String family = message.getOrEmpty("PV1-7-2");
        String given = message.getOrEmpty("PV1-7-3");
        String joined = (given + " " + family).trim();
        return joined.isEmpty() ? message.getOrEmpty("PV1-7-1") : joined;
    }

    /**
     * HL7 DT ({@code yyyyMMdd}) to ISO {@code yyyy-MM-dd}.
     *
     * <p>Partial dates are legal in HL7 — {@code 1974} and {@code 197403} both occur — so the
     * mapper widens rather than guesses. Padding a partial date to January 1st invents a
     * birthday, and paediatric dosing downstream is computed from it.
     */
    static String normaliseDate(String hl7Date) {
        if (hl7Date == null || hl7Date.isEmpty()) {
            return "";
        }
        String digits = hl7Date.length() > 8 ? hl7Date.substring(0, 8) : hl7Date;
        if (!digits.chars().allMatch(Character::isDigit)) {
            return "";
        }
        return switch (digits.length()) {
            case 4 -> digits;
            case 6 -> digits.substring(0, 4) + "-" + digits.substring(4, 6);
            case 8 -> digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
            default -> "";
        };
    }

    /**
     * HL7 DTM ({@code yyyyMMddHHmmss[.S][+/-ZZZZ]}) to an ISO-8601 string.
     *
     * <p>The offset is preserved when the sender supplies one and omitted when it does not.
     * Assuming UTC for an offset-less timestamp shifts every admission by the site's timezone,
     * which is how overnight admissions end up recorded on the wrong day.
     */
    static String normaliseTimestamp(String hl7Timestamp) {
        if (hl7Timestamp == null || hl7Timestamp.isEmpty()) {
            return "";
        }
        String value = hl7Timestamp.trim();
        String offset = "";
        int signIndex = Math.max(value.lastIndexOf('+'), value.lastIndexOf('-'));
        if (signIndex > 7) {
            offset = value.substring(signIndex);
            value = value.substring(0, signIndex);
            if (offset.length() == 5) {
                offset = offset.substring(0, 3) + ":" + offset.substring(3);
            }
        }
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        if (!value.chars().allMatch(Character::isDigit) || value.length() < 8) {
            return "";
        }
        String date =
                value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
        if (value.length() < 12) {
            return date + offset;
        }
        String time = value.substring(8, 10) + ":" + value.substring(10, 12);
        if (value.length() >= 14) {
            time = time + ":" + value.substring(12, 14);
        } else {
            time = time + ":00";
        }
        return date + "T" + time + offset;
    }
}
