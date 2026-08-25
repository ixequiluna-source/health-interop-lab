package ai.firmus.interop.hl7;

import java.util.List;
import java.util.Optional;

/**
 * One HL7 v2 segment, decomposed down to sub-component level.
 *
 * <p>The nesting mirrors the standard exactly: a segment holds fields, a field holds one or
 * more repetitions, a repetition holds components and a component holds sub-components.
 * Flattening any of those levels is the usual source of silent data loss in home-grown
 * interfaces (a second patient identifier in PID-3 or a second address line in PID-11 simply
 * disappears), so the model keeps all four.
 */
public record Segment(String name, List<Field> fields) {

    public record Field(List<Repetition> repetitions) {
        public Optional<Repetition> repetition(int oneBased) {
            return oneBased >= 1 && oneBased <= repetitions.size()
                    ? Optional.of(repetitions.get(oneBased - 1))
                    : Optional.empty();
        }
    }

    public record Repetition(List<Component> components) {
        public Optional<Component> component(int oneBased) {
            return oneBased >= 1 && oneBased <= components.size()
                    ? Optional.of(components.get(oneBased - 1))
                    : Optional.empty();
        }
    }

    public record Component(List<String> subComponents) {
        public Optional<String> subComponent(int oneBased) {
            return oneBased >= 1 && oneBased <= subComponents.size()
                    ? Optional.of(subComponents.get(oneBased - 1))
                    : Optional.empty();
        }

        /** The component's value; for a component with sub-components, the first one. */
        public String value() {
            return subComponents.isEmpty() ? "" : subComponents.get(0);
        }
    }

    /** Field {@code oneBased} using HL7 numbering (PID-3 is field 3). */
    public Optional<Field> field(int oneBased) {
        return oneBased >= 1 && oneBased <= fields.size()
                ? Optional.of(fields.get(oneBased - 1))
                : Optional.empty();
    }

    public int fieldCount() {
        return fields.size();
    }
}
