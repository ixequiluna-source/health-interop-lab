package ai.firmus.interop.hl7;

import java.util.Objects;

/**
 * The delimiter set declared by every HL7 v2 message in MSH-1 and MSH-2.
 *
 * <p>MSH-1 carries the field separator itself; MSH-2 carries, in order, the component,
 * repetition, escape and sub-component separators. The defaults below ({@code |^~\&}) are
 * by far the most common in the wild, but nothing in this codebase assumes them: every
 * parse reads the delimiters off the message it is given.
 */
public record EncodingCharacters(
        char field, char component, char repetition, char escape, char subComponent) {

    public static final EncodingCharacters DEFAULT =
            new EncodingCharacters('|', '^', '~', '\\', '&');

    public EncodingCharacters {
        char[] all = {field, component, repetition, escape, subComponent};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\r' || all[i] == '\n') {
                throw new Hl7ParseException("Segment terminators cannot be used as delimiters");
            }
            for (int j = i + 1; j < all.length; j++) {
                if (all[i] == all[j]) {
                    throw new Hl7ParseException(
                            "Duplicate HL7 delimiter '" + all[i] + "' in MSH-1/MSH-2");
                }
            }
        }
    }

    /**
     * Reads the delimiters from a raw message that starts with an MSH segment.
     *
     * @throws Hl7ParseException if the message is too short or does not start with MSH
     */
    public static EncodingCharacters from(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() < 8) {
            throw new Hl7ParseException("Message is too short to contain an MSH header");
        }
        if (!raw.startsWith("MSH")) {
            throw new Hl7ParseException("HL7 v2 messages must start with the MSH segment");
        }
        return new EncodingCharacters(
                raw.charAt(3), raw.charAt(4), raw.charAt(5), raw.charAt(6), raw.charAt(7));
    }

    /** The four characters that make up MSH-2, in HL7 order. */
    public String msh2() {
        return "" + component + repetition + escape + subComponent;
    }
}
