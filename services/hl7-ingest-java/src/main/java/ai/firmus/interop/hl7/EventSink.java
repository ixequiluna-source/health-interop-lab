package ai.firmus.interop.hl7;

/**
 * Where canonical events go once a message has been parsed and mapped.
 *
 * <p>The interface exists so the ingest path can be tested end to end — MLLP frame in, ACK out,
 * event captured — without a broker. Tests that need a real broker exercise
 * {@link KafkaEventSink} directly against a Kafka service container in CI, which is the only
 * way to catch serialization and partitioning mistakes that an in-memory double cannot.
 */
public interface EventSink extends AutoCloseable {

    /**
     * Publishes one event, blocking until the broker has acknowledged it.
     *
     * <p>Synchronous by design: the MLLP sender is waiting for an ACK, and returning AA before
     * the event is durable would tell the sending system the message is safe when it is not.
     * That is the classic way an interface silently loses admissions during a broker failover.
     *
     * @throws EventPublishException when the event could not be made durable
     */
    void publish(AdmissionEvent event);

    @Override
    default void close() {}

    /** Signals that the caller must not acknowledge the inbound message. */
    class EventPublishException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
