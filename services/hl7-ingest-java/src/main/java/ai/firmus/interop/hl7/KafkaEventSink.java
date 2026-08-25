package ai.firmus.interop.hl7;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Publishes canonical events to Kafka with the durability settings a clinical feed requires.
 *
 * <p>The producer configuration is the substance of this class, not the send call:
 *
 * <ul>
 *   <li>{@code acks=all} — the leader waits for the in-sync replicas. With {@code acks=1} a
 *       leader failure between write and replication loses acknowledged admissions.
 *   <li>{@code enable.idempotence=true} — a retry after a network timeout does not create a
 *       duplicate admission. Without it, the safe-looking combination of retries and timeouts
 *       silently duplicates events.
 *   <li>{@code max.in.flight.requests.per.connection=5} — the maximum the idempotent producer
 *       can keep while still guaranteeing order within a partition.
 * </ul>
 *
 * <p>Ordering matters because the partition key is the patient MRN: an A01 admit and a later
 * A08 update for the same patient must arrive in the order they happened.
 */
public final class KafkaEventSink implements EventSink {

    private final Producer<String, String> producer;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaEventSink(String bootstrapServers, String topic, Duration sendTimeout) {
        this(new KafkaProducer<>(producerConfig(bootstrapServers)), topic, sendTimeout);
    }

    /** Visible for tests, which inject a mock producer. */
    KafkaEventSink(Producer<String, String> producer, String topic, Duration sendTimeout) {
        this.producer = producer;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    static Properties producerConfig(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "hl7-ingest");
        return props;
    }

    @Override
    public void publish(AdmissionEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, event.partitionKey(), event.toJson());
        // Headers let consumers route and reject on schema version without parsing the body.
        for (Map.Entry<String, String> header :
                Map.of(
                                "schema-version", event.schemaVersion(),
                                "message-type", event.messageType(),
                                "event-id", event.eventId())
                        .entrySet()) {
            record.headers()
                    .add(
                            new RecordHeader(
                                    header.getKey(),
                                    header.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        try {
            producer.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted publishing event " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException("Failed to publish event " + event.eventId(), e);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
