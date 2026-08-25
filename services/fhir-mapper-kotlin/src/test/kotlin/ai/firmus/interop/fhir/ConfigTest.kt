package ai.firmus.interop.fhir

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `defaults are usable against a local stack`() {
        val config = Config.fromEnvironment(emptyMap())

        assertEquals("clinical.admissions.v1", config.kafkaTopic)
        assertEquals("clinical.admissions.v1.dlq", config.kafkaDlqTopic)
        assertEquals("fhir-mapper", config.kafkaGroupId)
        assertEquals("interop", config.mongoDatabase)
        assertEquals(ZoneId.of("UTC"), config.facilityZone)
        assertEquals("earliest", config.kafkaAutoOffsetReset)
        assertEquals(LogLevel.INFO, config.logLevel)
        // Off by default: an exception message from a driver or a parser can quote PHI.
        assertFalse(config.logExternalErrorMessages)
    }

    @Test
    fun `reads every documented variable`() {
        val config = Config.fromEnvironment(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to "broker-1:9092,broker-2:9092",
                "KAFKA_TOPIC" to "adt.in",
                "KAFKA_DLQ_TOPIC" to "adt.in.dlq",
                "KAFKA_GROUP_ID" to "mapper-eu",
                "KAFKA_MAX_POLL_RECORDS" to "10",
                "MONGODB_DATABASE" to "clinical",
                "FACILITY_TIMEZONE" to "America/Mexico_City",
                "LOG_LEVEL" to "debug",
                "HTTP_PORT" to "9000",
            ),
        )

        assertEquals("broker-1:9092,broker-2:9092", config.kafkaBootstrapServers)
        assertEquals("adt.in", config.kafkaTopic)
        assertEquals("mapper-eu", config.kafkaGroupId)
        assertEquals(10, config.maxPollRecords)
        assertEquals(ZoneId.of("America/Mexico_City"), config.facilityZone)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertEquals(9000, config.httpPort)
    }

    @Test
    fun `treats an empty variable as unset`() {
        // A Kubernetes ConfigMap key with no value arrives as "", and taking it literally would set
        // the topic to the empty string rather than the default.
        val config = Config.fromEnvironment(mapOf("KAFKA_TOPIC" to "", "MONGODB_DATABASE" to "   "))

        assertEquals("clinical.admissions.v1", config.kafkaTopic)
        assertEquals("interop", config.mongoDatabase)
    }

    @Test
    fun `refuses a dead-letter topic equal to the source topic`() {
        // Otherwise every poison message is republished onto the topic it came from and loops at
        // the full throughput of the consumer.
        val error = assertFailsWith<ConfigException> {
            Config.fromEnvironment(mapOf("KAFKA_TOPIC" to "adt.in", "KAFKA_DLQ_TOPIC" to "adt.in"))
        }
        assertTrue(error.message!!.contains("KAFKA_DLQ_TOPIC"))
    }

    /**
     * The arithmetic that prevents a rebalance storm. Retries happen inside the poll cycle, so a
     * full batch of records each burning its whole retry budget has to finish well before Kafka
     * evicts the consumer for missing `max.poll.interval.ms`.
     */
    @Test
    fun `refuses a retry budget that could outlast the poll interval`() {
        val error = assertFailsWith<ConfigException> {
            Config.fromEnvironment(
                mapOf(
                    "KAFKA_MAX_POLL_RECORDS" to "500",
                    "WRITE_RETRY_ATTEMPTS" to "6",
                    "WRITE_RETRY_BASE_BACKOFF_MS" to "1000",
                    "WRITE_RETRY_MAX_BACKOFF_MS" to "30000",
                    "KAFKA_MAX_POLL_INTERVAL_MS" to "300000",
                ),
            )
        }
        assertTrue(error.message!!.contains("retry budget"), error.message!!)
    }

    @Test
    fun `accepts a retry budget that fits`() {
        val config = Config.fromEnvironment(
            mapOf(
                "KAFKA_MAX_POLL_RECORDS" to "25",
                "WRITE_RETRY_ATTEMPTS" to "4",
                "WRITE_RETRY_BASE_BACKOFF_MS" to "200",
                "WRITE_RETRY_MAX_BACKOFF_MS" to "2000",
                "KAFKA_MAX_POLL_INTERVAL_MS" to "300000",
            ),
        )

        assertTrue(
            config.maxPollRecords * config.retry.worstCaseMillis() <=
                config.maxPollIntervalMillis * Config.POLL_BUDGET_FRACTION,
        )
    }

    @Test
    fun `rejects values it cannot use`() {
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("KAFKA_MAX_POLL_RECORDS" to "lots")) }
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("KAFKA_MAX_POLL_RECORDS" to "0")) }
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("FACILITY_TIMEZONE" to "Mars/Olympus")) }
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("LOG_LEVEL" to "chatty")) }
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("KAFKA_AUTO_OFFSET_RESET" to "beginning")) }
        assertFailsWith<ConfigException> { Config.fromEnvironment(mapOf("LOG_EXTERNAL_ERROR_MESSAGES" to "maybe")) }
    }

    /**
     * A connection string routinely carries credentials, and dumping the config at startup is one
     * of the first things anyone adds.
     */
    @Test
    fun `toString does not disclose the Mongo connection string`() {
        val config = Config.fromEnvironment(
            mapOf("MONGODB_URI" to "mongodb://svc:hunter2@mongo-0.internal:27017/?authSource=admin"),
        )

        assertFalse(config.toString().contains("hunter2"), config.toString())
        assertFalse(config.toString().contains("mongo-0.internal"), config.toString())
    }

    // --- backoff ------------------------------------------------------------------------------

    @Test
    fun `backoff doubles and then stops at the ceiling`() {
        val policy = RetryPolicy(attempts = 6, baseBackoffMillis = 100, maxBackoffMillis = 500)

        assertEquals(listOf(100L, 200L, 400L, 500L, 500L, 500L), (1..6).map { policy.backoffMillis(it) })
    }

    @Test
    fun `backoff cannot overflow into a negative sleep`() {
        // A large attempt count with a large base shifts past Long's range and wraps negative;
        // Thread.sleep throws on a negative argument, so the clamp is not cosmetic.
        val policy = RetryPolicy(attempts = 64, baseBackoffMillis = 1L shl 45, maxBackoffMillis = 1L shl 46)

        assertTrue((1..64).all { policy.backoffMillis(it) >= 0 }, "negative backoff")
    }

    @Test
    fun `a retry policy must make at least one attempt`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(0, 100, 100) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(3, 500, 100) }
    }
}
