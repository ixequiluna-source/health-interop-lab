package ai.firmus.interop.hl7;

import java.util.ArrayList;
import java.util.List;

/**
 * A dependency-free parser for HL7 v2.x ER7 ("pipe and hat") encoding.
 *
 * <p>Three details account for most of the interoperability bugs this class exists to avoid:
 *
 * <ul>
 *   <li><b>MSH is off by one.</b> MSH-1 <em>is</em> the field separator, so the token layout of
 *       the header differs from every other segment. Parsers that split uniformly report
 *       MSH-9 where the sender wrote MSH-10, and the ACK then references the wrong control id.
 *   <li><b>MSH-2 must not be split.</b> It contains the delimiter characters themselves as
 *       data. Splitting it yields four empty components and destroys the message's own
 *       description of its encoding.
 *   <li><b>Escape sequences are resolved last.</b> Delimiters that appear in data arrive
 *       escaped ({@code \F\}, {@code \S\}, …), so the structural split runs on raw text and
 *       decoding happens at the leaves. Decoding first would re-introduce delimiters into
 *       data and shift every field after it.
 * </ul>
 *
 * <p>Segment terminators are carriage returns per the standard; LF and CRLF are accepted too,
 * because real senders emit them and rejecting the message helps nobody.
 */
public final class Hl7Parser {

    private static final int MAX_SEGMENTS = 10_000;

    private Hl7Parser() {}

    public static Hl7Message parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException("Empty HL7 payload");
        }
        String normalised = raw.replace("\r\n", "\r").replace('\n', '\r');
        EncodingCharacters enc = EncodingCharacters.from(normalised);

        String[] rawSegments = normalised.split("\r");
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < rawSegments.length; i++) {
            String line = rawSegments[i];
            if (line.isBlank()) {
                continue;
            }
            if (segments.size() >= MAX_SEGMENTS) {
                throw new Hl7ParseException("Message exceeds " + MAX_SEGMENTS + " segments");
            }
            segments.add(parseSegment(line, enc, i));
        }
        if (segments.isEmpty()) {
            throw new Hl7ParseException("Message contains no segments");
        }
        if (!segments.get(0).name().equals("MSH")) {
            throw new Hl7ParseException("First segment must be MSH");
        }
        return new Hl7Message(enc, List.copyOf(segments));
    }

    private static Segment parseSegment(String line, EncodingCharacters enc, int index) {
        String[] tokens = splitLiteral(line, enc.field());
        String name = tokens[0];
        if (name.length() != 3) {
            throw new Hl7ParseException("Segment name must be 3 characters, got '" + name + "'", index);
        }
        boolean isHeader = name.equals("MSH");

        List<Segment.Field> fields = new ArrayList<>();
        if (isHeader) {
            // MSH-1 is the field separator itself; it is never present as a token.
            fields.add(literalField(String.valueOf(enc.field())));
            // MSH-2 holds the delimiter characters as data and must survive verbatim.
            fields.add(literalField(tokens.length > 1 ? tokens[1] : enc.msh2()));
        }

        int firstDataToken = isHeader ? 2 : 1;
        for (int i = firstDataToken; i < tokens.length; i++) {
            fields.add(parseField(tokens[i], enc));
        }
        return new Segment(name, List.copyOf(fields));
    }

    /** A field carrying one component of one repetition, kept exactly as written. */
    private static Segment.Field literalField(String value) {
        return new Segment.Field(
                List.of(new Segment.Repetition(List.of(new Segment.Component(List.of(value))))));
    }

    private static Segment.Field parseField(String raw, EncodingCharacters enc) {
        List<Segment.Repetition> repetitions = new ArrayList<>();
        for (String rep : splitLiteral(raw, enc.repetition())) {
            List<Segment.Component> components = new ArrayList<>();
            for (String comp : splitLiteral(rep, enc.component())) {
                List<String> subComponents = new ArrayList<>();
                for (String sub : splitLiteral(comp, enc.subComponent())) {
                    subComponents.add(unescape(sub, enc));
                }
                components.add(new Segment.Component(List.copyOf(subComponents)));
            }
            repetitions.add(new Segment.Repetition(List.copyOf(components)));
        }
        return new Segment.Field(List.copyOf(repetitions));
    }

    /**
     * Splits on a literal character, keeping trailing empties.
     *
     * <p>{@code String.split} discards trailing empty strings, which in HL7 silently deletes
     * the intentionally-empty trailing fields that distinguish "not sent" from "sent empty".
     */
    static String[] splitLiteral(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == delimiter) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * Resolves HL7 escape sequences into their literal characters.
     *
     * <p>Unknown sequences are returned untouched rather than dropped: a {@code \Zxx\} local
     * escape carries site-specific meaning and destroying it loses clinical information.
     */
    static String unescape(String value, EncodingCharacters enc) {
        char esc = enc.escape();
        if (value.indexOf(esc) < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != esc) {
                out.append(c);
                i++;
                continue;
            }
            int end = value.indexOf(esc, i + 1);
            if (end < 0) {
                // Unterminated escape: emit the remainder verbatim.
                out.append(value, i, value.length());
                break;
            }
            String body = value.substring(i + 1, end);
            out.append(decodeEscape(body, enc));
            i = end + 1;
        }
        return out.toString();
    }

    private static String decodeEscape(String body, EncodingCharacters enc) {
        if (body.isEmpty()) {
            return "";
        }
        switch (body) {
            case "F":
                return String.valueOf(enc.field());
            case "S":
                return String.valueOf(enc.component());
            case "T":
                return String.valueOf(enc.subComponent());
            case "R":
                return String.valueOf(enc.repetition());
            case "E":
                return String.valueOf(enc.escape());
            case ".br":
            case ".sp":
                return "\n";
            default:
                break;
        }
        if (body.charAt(0) == 'X' && body.length() > 1 && body.length() % 2 == 1) {
            try {
                StringBuilder hex = new StringBuilder();
                for (int j = 1; j < body.length(); j += 2) {
                    hex.append((char) Integer.parseInt(body.substring(j, j + 2), 16));
                }
                return hex.toString();
            } catch (NumberFormatException ignored) {
                // Fall through and preserve the original sequence.
            }
        }
        return enc.escape() + body + enc.escape();
    }

    /** Escapes a literal value for safe placement inside an ER7 field. */
    public static String escape(String value, EncodingCharacters enc) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == enc.escape()) {
                out.append(enc.escape()).append('E').append(enc.escape());
            } else if (c == enc.field()) {
                out.append(enc.escape()).append('F').append(enc.escape());
            } else if (c == enc.component()) {
                out.append(enc.escape()).append('S').append(enc.escape());
            } else if (c == enc.subComponent()) {
                out.append(enc.escape()).append('T').append(enc.escape());
            } else if (c == enc.repetition()) {
                out.append(enc.escape()).append('R').append(enc.escape());
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
