package ai.firmus.interop.fhir

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

/**
 * Kafka client configuration.
 *
 * Every setting below that differs from a client default is here because a default is wrong for a
 * clinical data path, and each one is named with the failure it prevents. Settings that match the
 * default are stated anyway when the value is load-bearing, so that a later "tidy-up" has to
 * delete a documented line rather than an invisible assumption — the same discipline the Java
 * producer's `KafkaEventSinkConfigTest` enforces on the other end of this topic.
 */
object KafkaClients {

    fun consumer(config: Config): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, config.kafkaGroupId)
            put(CommonClientConfigs.CLIENT_ID_CONFIG, config.kafkaClientId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)

            // The whole commit design depends on this being false. See AdmissionConsumer.
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

            // Only consulted when the group has no committed offset. `earliest` means a new
            // deployment backfills the read model from the topic's retained history rather than
            // starting blank; `latest` would silently skip every admission that arrived before the
            // service was first deployed.
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.kafkaAutoOffsetReset)

            // Bounds the batch, and therefore bounds the worst-case time between two poll() calls.
            // Config.validate() checks that bound against MAX_POLL_INTERVAL_MS_CONFIG.
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.maxPollRecords.toString())
            put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, config.maxPollIntervalMillis.toString())

            // Cooperative rebalancing: a member joining or leaving revokes only the partitions that
            // actually move, instead of every consumer dropping everything and re-acquiring. With
            // eager assignment, a rolling restart of N replicas stops the whole group N times.
            put(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
            )

            // Detects a hung consumer within 45s while tolerating an ordinary GC pause. The
            // heartbeat thread is separate from the poll loop, so this is independent of how long a
            // batch takes — that is what MAX_POLL_INTERVAL_MS covers.
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "45000")
            put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000")

            // The producer on the other side is idempotent but not transactional today. Reading
            // committed costs nothing now and means turning on transactions upstream does not
            // require a matching change and redeploy here.
            put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")

            // A typo in KAFKA_TOPIC must fail loudly. With auto-creation enabled the broker
            // conjures the misspelled topic, the consumer subscribes happily, and the service sits
            // at zero lag consuming nothing at all.
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false")
        }
        return KafkaConsumer(props)
    }

    /**
     * The dead-letter producer.
     *
     * Durability settings mirror the ingest service's producer, for the same reason: a
     * dead-lettered message that is acknowledged by a leader and then lost in a failover is the
     * one copy of an admission nobody will ever look for again.
     */
    fun deadLetterProducer(config: Config): Producer<String, String> {
        val props = Properties().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers)
            put(CommonClientConfigs.CLIENT_ID_CONFIG, "${config.kafkaClientId}-dlq")
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)

            // acks=all with idempotence: the record is on every in-sync replica before the send
            // completes, and a retry inside the client cannot duplicate or reorder it.
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")

            // Retry until the delivery timeout rather than a retry count. A count is a duration in
            // disguise whose length depends on the request timeout, which is how "retries=3" turns
            // out to mean "give up after 400ms" during a leader election.
            put(ProducerConfig.RETRIES_CONFIG, Int.MAX_VALUE.toString())
            put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000")
            put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000")

            // No batching delay. The consumer loop blocks on each send, so lingering to fill a
            // batch would add latency to the poll cycle and never fill anything: dead letters
            // arrive one at a time, and if they do not, there is a bigger problem.
            put(ProducerConfig.LINGER_MS_CONFIG, "0")
        }
        return KafkaProducer(props)
    }
}
