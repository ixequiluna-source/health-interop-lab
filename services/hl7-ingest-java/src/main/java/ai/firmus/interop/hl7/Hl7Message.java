package ai.firmus.interop.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed HL7 v2 message plus terse, total accessors for reading values out of it.
 *
 * <p>Every accessor returns {@link Optional} or an empty string rather than throwing: in a
 * clinical interface an absent optional field is a normal, expected state, and turning it
 * into an exception pushes null-handling into every call site.
 */
public record Hl7Message(EncodingCharacters encoding, List<Segment> segments) {

    /** {@code SEG-field[(rep)][-component[-subcomponent]]}, e.g. {@code PID-3(2)-1}. */
    private static final Pattern PATH =
            Pattern.compile("^([A-Z][A-Z0-9]{2})-(\\d+)(?:\\((\\d+)\\))?(?:-(\\d+))?(?:-(\\d+))?$");

    /** First segment with the given three-letter name. */
    public Optional<Segment> segment(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** Every segment with the given name, in message order. */
    public List<Segment> allSegments(String name) {
        List<Segment> found = new ArrayList<>();
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                found.add(s);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reads a value by HL7 path.
     *
     * <p>Omitted repetition defaults to the first, omitted component to the first, omitted
     * sub-component to the first. {@code get("PID-5-1")} therefore returns the family name of
     * the first repetition of PID-5, which is what the standard says it means.
     */
    public Optional<String> get(String path) {
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            throw new IllegalArgumentException("Malformed HL7 path: " + path);
        }
        String segmentName = m.group(1);
        int field = Integer.parseInt(m.group(2));
        int repetition = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        int component = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        int subComponent = m.group(5) == null ? 1 : Integer.parseInt(m.group(5));

        return segment(segmentName)
                .flatMap(s -> s.field(field))
                .flatMap(f -> f.repetition(repetition))
                .flatMap(r -> r.component(component))
                .flatMap(c -> c.subComponent(subComponent))
                .filter(v -> !v.isEmpty());
    }

    /** {@link #get(String)} with an empty string instead of an empty optional. */
    public String getOrEmpty(String path) {
        return get(path).orElse("");
    }

    /** All repetitions of a field, read at the requested component. */
    public List<String> getRepetitions(String segmentName, int field, int component) {
        return segment(segmentName)
                .flatMap(s -> s.field(field))
                .map(Segment.Field::repetitions)
                .orElse(List.of())
                .stream()
                .map(r -> r.component(component).map(Segment.Component::value).orElse(""))
                .filter(v -> !v.isEmpty())
                .toList();
    }

    /** MSH-9-1, e.g. {@code ADT}. */
    public String messageCode() {
        return getOrEmpty("MSH-9-1");
    }

    /** MSH-9-2, e.g. {@code A01}. */
    public String triggerEvent() {
        return getOrEmpty("MSH-9-2");
    }

    /** MSH-10, the sender-assigned message control id echoed back in the ACK. */
    public String controlId() {
        return getOrEmpty("MSH-10");
    }

    /** {@code ADT^A01} — the pair that decides how a message must be handled. */
    public String messageType() {
        String code = messageCode();
        String trigger = triggerEvent();
        return trigger.isEmpty() ? code : code + "^" + trigger;
    }
}
