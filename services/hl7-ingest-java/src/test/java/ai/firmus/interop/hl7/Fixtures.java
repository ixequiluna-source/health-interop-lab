package ai.firmus.interop.hl7;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/** Sample messages shared across tests, written the way real senders emit them. */
final class Fixtures {

    static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-25T14:30:00Z"), ZoneOffset.UTC);

    private Fixtures() {}

    /**
     * Builds a segment from explicit field positions.
     *
     * <p>PV1 puts the visit number at field 19 and the admit timestamp at field 44, which in
     * literal ER7 means counting out thirty-odd consecutive pipes by eye. Miscounting produces
     * a fixture that "passes" against an equally miscounted parser, so the positions are
     * declared here and the separators are generated.
     */
    static String segment(String name, Map<Integer, String> fieldsByPosition) {
        int highest = fieldsByPosition.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        StringBuilder sb = new StringBuilder(name);
        for (int i = 1; i <= highest; i++) {
            sb.append('|').append(fieldsByPosition.getOrDefault(i, ""));
        }
        return sb.toString();
    }

    private static Map<Integer, String> fields(Object... positionThenValue) {
        Map<Integer, String> map = new TreeMap<>();
        for (int i = 0; i < positionThenValue.length; i += 2) {
            map.put((Integer) positionThenValue[i], (String) positionThenValue[i + 1]);
        }
        return map;
    }

    static final String PV1_ADMIT =
            segment(
                    "PV1",
                    fields(
                            1, "1",
                            2, "I",
                            3, "WARD-3^301^A^HGS_PUEBLA",
                            7, "1234^Torres^Enrique^R",
                            19, "VN-556677",
                            44, "20260825143000"));

    static final String PID_ADMIT =
            segment(
                    "PID",
                    fields(
                            1, "1",
                            3, "MRN-88213^^^HGS^MR~NSS-4471120^^^IMSS^SS",
                            5, "Luna^Ixequi^M",
                            7, "19900314",
                            8, "M",
                            11, "Av. Juarez 1500^^Puebla^PUE^72160^MX"));

    /** A complete ADT^A01 admit with repeating identifiers and a compound location. */
    static final String ADT_A01 =
            String.join(
                    "\r",
                    "MSH|^~\\&|EPIC_ADT|HGS_PUEBLA|INTEROP_LAB|FIRMUS|20260825143000||ADT^A01|MSG00001|P|2.5.1",
                    "EVN|A01|20260825143000",
                    PID_ADMIT,
                    PV1_ADMIT);

    /** Same admit, LF-terminated: common from senders that pass HL7 through text tooling. */
    static final String ADT_A01_LF = ADT_A01.replace('\r', '\n');

    /** A trigger this service deliberately refuses: transfer-cancel is not an admission. */
    static final String ADT_A11 = ADT_A01.replace("ADT^A01", "ADT^A11");

    /** Right shape, wrong message: an observation result, not an ADT. */
    static final String ORU_R01 = ADT_A01.replace("ADT^A01", "ORU^R01");

    /** An ADT^A01 with no PID segment at all. */
    static final String ADT_A01_NO_PID =
            String.join(
                    "\r",
                    "MSH|^~\\&|EPIC_ADT|HGS_PUEBLA|INTEROP_LAB|FIRMUS|20260825143000||ADT^A01|MSG00003|P|2.5.1",
                    "EVN|A01|20260825143000",
                    PV1_ADMIT);

    /** An ADT^A01 whose PID-3 is present but empty. */
    static final String ADT_A01_NO_IDENTIFIER =
            String.join(
                    "\r",
                    "MSH|^~\\&|EPIC_ADT|HGS_PUEBLA|INTEROP_LAB|FIRMUS|20260825143000||ADT^A01|MSG00004|P|2.5.1",
                    segment("PID", Map.of(1, "1", 5, "Luna^Ixequi^M")),
                    PV1_ADMIT);

    /** Non-default delimiters. Nothing in the parser may assume {@code |^~\&}. */
    static final String ADT_A01_CUSTOM_DELIMITERS =
            String.join(
                    "\r",
                    "MSH#@~\\&#EPIC_ADT#HGS_PUEBLA#INTEROP_LAB#FIRMUS#20260825143000##ADT@A01#MSG00002#P#2.5.1",
                    "PID#1##MRN-88213#N#Luna@Ixequi##19900314#M",
                    "PV1#1#I#WARD-3@301@A");
}
