package ai.firmus.interop.hl7;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The whole ingest decision, in one place: parse, map, publish, acknowledge.
 *
 * <p>The error taxonomy is the point. HL7 senders behave differently depending on which
 * acknowledgement code they get back, so collapsing every failure into one code makes the feed
 * either lose messages or wedge:
 *
 * <ul>
 *   <li><b>AR (reject)</b> — the payload is not valid HL7. Retrying it unchanged cannot help,
 *       and the sender should move it to an error queue for a human.
 *   <li><b>AE (error)</b> — valid HL7 this service will not process, such as an unsupported
 *       trigger. Also not retryable, but distinguishable in the sender's monitoring.
 *   <li><b>no ACK</b> — the event could not be made durable. The connection is dropped without
 *       an acknowledgement so the sender retries. Answering AA here would tell the sender the
 *       admission is safe when it was never written.
 * </ul>
 */
public final class IngestHandler {

    private static final Logger LOG = Logger.getLogger(IngestHandler.class.getName());

    private final AdtMapper mapper;
    private final AckBuilder ackBuilder;
    private final EventSink sink;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public IngestHandler(AdtMapper mapper, AckBuilder ackBuilder, EventSink sink) {
        this.mapper = mapper;
        this.ackBuilder = ackBuilder;
        this.sink = sink;
    }

    /** Thrown when no acknowledgement may be sent, so the sender retries. */
    public static final class RetryableFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RetryableFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @return the ER7 acknowledgement to send back
     * @throws RetryableFailure when the sender must retry and must not be acknowledged
     */
    public String handle(String rawMessage) {
        Hl7Message parsed;
        try {
            parsed = Hl7Parser.parse(rawMessage);
        } catch (Hl7ParseException e) {
            rejected.incrementAndGet();
            LOG.log(Level.WARNING, "Rejecting unparseable HL7 payload: {0}", e.getMessage());
            return ackBuilder.reject(e.getMessage());
        }

        AdmissionEvent event;
        try {
            event = mapper.map(parsed);
        } catch (Hl7ParseException e) {
            rejected.incrementAndGet();
            LOG.log(
                    Level.WARNING,
                    "Refusing message {0}: {1}",
                    new Object[] {parsed.controlId(), e.getMessage()});
            return ackBuilder.error(parsed, e.getMessage());
        }

        try {
            sink.publish(event);
        } catch (EventSink.EventPublishException e) {
            failed.incrementAndGet();
            LOG.log(Level.SEVERE, "Publish failed; withholding ACK so the sender retries", e);
            throw new RetryableFailure("Could not publish event " + event.eventId(), e);
        }

        accepted.incrementAndGet();
        // Log the control id and event id, never the message body: it is PHI.
        LOG.log(
                Level.INFO,
                "Accepted {0} control-id={1} event-id={2}",
                new Object[] {event.messageType(), event.messageControlId(), event.eventId()});
        return ackBuilder.accept(parsed);
    }

    public long acceptedCount() {
        return accepted.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long failedCount() {
        return failed.get();
    }
}
