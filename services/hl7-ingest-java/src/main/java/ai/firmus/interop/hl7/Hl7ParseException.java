package ai.firmus.interop.hl7;

/** Raised when an ER7 payload cannot be parsed into a well-formed HL7 v2 message. */
public class Hl7ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int segmentIndex;

    public Hl7ParseException(String message) {
        this(message, -1);
    }

    public Hl7ParseException(String message, int segmentIndex) {
        super(segmentIndex >= 0 ? message + " (segment " + segmentIndex + ")" : message);
        this.segmentIndex = segmentIndex;
    }

    /** Zero-based index of the offending segment, or -1 when not segment-specific. */
    public int segmentIndex() {
        return segmentIndex;
    }
}
