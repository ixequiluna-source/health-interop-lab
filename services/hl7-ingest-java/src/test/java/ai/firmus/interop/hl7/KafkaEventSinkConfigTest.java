package ai.firmus.interop.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the producer's durability contract.
 *
 * <p>These four settings are the difference between a feed that loses or duplicates admissions
 * under failure and one that does not, and none of them is the client default. Pinning them in
 * a test means a future "harmless" config tidy-up fails the build instead of quietly
 * downgrading delivery guarantees in production.
 */
class KafkaEventSinkConfigTest {

    private final Properties config = KafkaEventSink.producerConfig("broker:9092");

    @Test
    @DisplayName("acks=all: a leader failure after the write cannot lose an acknowledged admit")
    void waitsForAllInSyncReplicas() {
        assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
    }

    @Test
    @DisplayName("idempotence: a retry after a timeout does not duplicate an admission")
    void isIdempotent() {
        assertEquals(true, config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }

    @Test
    @DisplayName("in-flight requests stay within the limit that preserves per-partition order")
    void preservesOrdering() {
        int inFlight = (int) config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        assertTrue(inFlight <= 5, "the idempotent producer only guarantees order up to 5");
    }

    @Test
    void retriesUntilTheDeliveryTimeout() {
        assertEquals(Integer.MAX_VALUE, config.get(ProducerConfig.RETRIES_CONFIG));
        assertEquals(120_000, config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
    }

    @Test
    void carriesTheBootstrapServers() {
        assertEquals("broker:9092", config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }
}
