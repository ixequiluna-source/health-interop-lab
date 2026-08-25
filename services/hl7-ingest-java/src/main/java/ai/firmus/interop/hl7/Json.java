package ai.firmus.interop.hl7;

import java.util.List;
import java.util.Map;

/**
 * A small, correct JSON writer.
 *
 * <p>Deliberately hand-written: this service's only job on the wire is to emit one well-defined
 * envelope, and a JSON library would be a supply-chain dependency in a HIPAA-scoped ingest path
 * for no gain. Escaping follows RFC 8259, including the control characters below 0x20 that
 * naive {@code replace("\"", "\\\"")} implementations miss — and which do occur in HL7 free
 * text such as NTE notes.
 */
public final class Json {

    private Json() {}

    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String array(List<String> encodedValues) {
        return "[" + String.join(",", encodedValues) + "]";
    }

    public static String stringArray(List<String> values) {
        return array(values.stream().map(Json::string).toList());
    }

    /**
     * Renders an object from already-encoded values, dropping entries whose value is null.
     *
     * <p>Omitting absent fields rather than emitting {@code null} keeps the downstream FHIR
     * mapper's contract simple: present means the sender supplied it.
     */
    public static String object(Map<String, String> encodedFields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : encodedFields.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(string(e.getKey())).append(':').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    /** Encodes a value, or null so {@link #object(Map)} omits the key entirely. */
    public static String optional(String value) {
        return value == null || value.isEmpty() ? null : string(value);
    }
}
