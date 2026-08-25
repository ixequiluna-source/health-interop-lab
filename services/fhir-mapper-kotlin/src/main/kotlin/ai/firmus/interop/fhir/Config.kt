package ai.firmus.interop.fhir

import java.time.DateTimeException
import java.time.ZoneId

/** Raised for a configuration that cannot produce a correct service. */
class ConfigException(message: String) : RuntimeException(message)

/**
 * How long a retry may take, and how many times it may be attempted.
 *
 * Backoff is exponential with a ceiling. The ceiling matters more than it looks: retries here
 * happen *inside* a `poll()` cycle, so unbounded backoff silently converts a slow database into
 * a consumer-group rebalance (see [Config.validate]).
 */
data class RetryPolicy(
    val attempts: Int,
    val baseBackoffMillis: Long,
    val maxBackoffMillis: Long,
) {
    init {
        require(attempts >= 1) { "attempts must be at least 1" }
        require(baseBackoffMillis >= 0) { "baseBackoffMillis must not be negative" }
        require(maxBackoffMillis >= baseBackoffMillis) { "maxBackoffMillis must be >= baseBackoffMillis" }
    }

    /** Backoff before attempt number [attempt], counting from 1. */
    fun backoffMillis(attempt: Int): Long {
        if (attempt <= 1) return baseBackoffMillis
        // Shift rather than pow, and clamp the exponent, so a large `attempts` cannot overflow
        // into a negative sleep.
        val shift = (attempt - 1).coerceAtMost(20)
        val scaled = baseBackoffMillis shl shift
        return if (scaled <= 0) maxBackoffMillis else scaled.coerceAtMost(maxBackoffMillis)
    }

    /** Worst-case wall time spent retrying a single record before giving up. */
    fun worstCaseMillis(): Long = (1..attempts).sumOf { backoffMillis(it) }
}

/**
 * Every knob the service has, read once from the environment at startup.
 *
 * Configuration is a value, not a lookup: nothing in the service calls `System.getenv` after
 * this, so the whole configuration surface is visible in one type, testable without touching
 * the process environment, and impossible to change halfway through a run.
 */
data class Config(
    val kafkaBootstrapServers: String,
    val kafkaTopic: String,
    val kafkaDlqTopic: String,
    val kafkaGroupId: String,
    val kafkaClientId: String,
    val kafkaAutoOffsetReset: String,
    val maxPollRecords: Int,
    val maxPollIntervalMillis: Int,
    val pollTimeoutMillis: Long,
    val mongoUri: String,
    val mongoDatabase: String,
    val identifierSystemBase: String,
    val defaultAssigningAuthority: String,
    val facilityZone: ZoneId,
    val retry: RetryPolicy,
    val shutdownTimeoutMillis: Long,
    val httpPort: Int,
    val logLevel: LogLevel,
    val logExternalErrorMessages: Boolean,
) {
    /**
     * `toString` is redacted because [mongoUri] routinely contains credentials, and a config
     * dump is one of the first things anyone adds to a startup log.
     */
    override fun toString(): String =
        "Config(topic=$kafkaTopic, dlq=$kafkaDlqTopic, group=$kafkaGroupId, " +
            "database=$mongoDatabase, facilityZone=$facilityZone, maxPollRecords=$maxPollRecords)"

    /**
     * Rejects combinations that produce a service which looks healthy and is not.
     *
     * The interesting one is the poll budget. Kafka evicts a consumer that does not call
     * `poll()` within `max.poll.interval.ms`, and eviction triggers a rebalance, which revokes
     * the partitions mid-batch, which makes the surviving consumers re-process the batch and
     * hit the same slow dependency — a rebalance storm that looks like a Kafka problem and is
     * actually a timeout arithmetic problem. Retrying every record in a full batch has to fit
     * inside the interval with room to spare, so the check is made at startup where it is a
     * one-line failure instead of at 3am where it is an outage.
     */
    fun validate() {
        if (kafkaTopic.isBlank()) throw ConfigException("KAFKA_TOPIC must not be blank")
        if (kafkaDlqTopic.isBlank()) throw ConfigException("KAFKA_DLQ_TOPIC must not be blank")
        if (kafkaDlqTopic == kafkaTopic) {
            // Otherwise a poison message is republished to the topic it came from and loops
            // forever, at the full throughput of the consumer.
            throw ConfigException("KAFKA_DLQ_TOPIC must differ from KAFKA_TOPIC")
        }
        if (kafkaGroupId.isBlank()) throw ConfigException("KAFKA_GROUP_ID must not be blank")
        if (mongoDatabase.isBlank()) throw ConfigException("MONGODB_DATABASE must not be blank")
        if (maxPollRecords < 1) throw ConfigException("KAFKA_MAX_POLL_RECORDS must be at least 1")
        if (kafkaAutoOffsetReset !in setOf("earliest", "latest", "none")) {
            throw ConfigException("KAFKA_AUTO_OFFSET_RESET must be earliest, latest or none")
        }

        val worstCaseBatchMillis = maxPollRecords.toLong() * retry.worstCaseMillis()
        val budget = (maxPollIntervalMillis * POLL_BUDGET_FRACTION).toLong()
        if (worstCaseBatchMillis > budget) {
            throw ConfigException(
                "retry budget too large: $maxPollRecords records x ${retry.worstCaseMillis()}ms " +
                    "= ${worstCaseBatchMillis}ms exceeds ${budget}ms " +
                    "(${(POLL_BUDGET_FRACTION * 100).toInt()}% of KAFKA_MAX_POLL_INTERVAL_MS=$maxPollIntervalMillis). " +
                    "Lower KAFKA_MAX_POLL_RECORDS or WRITE_RETRY_ATTEMPTS, or raise the interval.",
            )
        }
    }

    companion object {
        /**
         * Retries may consume at most this share of the poll interval. The remainder is the
         * margin for the writes themselves, GC pauses and a slow broker round trip.
         */
        const val POLL_BUDGET_FRACTION: Double = 0.5

        fun fromEnvironment(env: Map<String, String> = System.getenv()): Config {
            fun str(key: String, default: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default

            fun int(key: String, default: Int): Int {
                val raw = str(key, default.toString())
                return raw.toIntOrNull() ?: throw ConfigException("$key must be an integer, got '$raw'")
            }

            fun long(key: String, default: Long): Long {
                val raw = str(key, default.toString())
                return raw.toLongOrNull() ?: throw ConfigException("$key must be an integer, got '$raw'")
            }

            fun bool(key: String, default: Boolean): Boolean {
                val raw = str(key, default.toString()).lowercase()
                return when (raw) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> throw ConfigException("$key must be a boolean, got '$raw'")
                }
            }

            val zoneId = str("FACILITY_TIMEZONE", "UTC")
            val zone = try {
                ZoneId.of(zoneId)
            } catch (_: DateTimeException) {
                throw ConfigException("FACILITY_TIMEZONE is not a known zone id: '$zoneId'")
            }

            val logLevelName = str("LOG_LEVEL", LogLevel.INFO.name).uppercase()
            val logLevel = LogLevel.entries.firstOrNull { it.name == logLevelName }
                ?: throw ConfigException("LOG_LEVEL must be one of ${LogLevel.entries.joinToString(",")}")

            val config = Config(
                kafkaBootstrapServers = str("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                kafkaTopic = str("KAFKA_TOPIC", "clinical.admissions.v1"),
                kafkaDlqTopic = str("KAFKA_DLQ_TOPIC", "clinical.admissions.v1.dlq"),
                kafkaGroupId = str("KAFKA_GROUP_ID", "fhir-mapper"),
                kafkaClientId = str("KAFKA_CLIENT_ID", defaultClientId(env)),
                kafkaAutoOffsetReset = str("KAFKA_AUTO_OFFSET_RESET", "earliest"),
                maxPollRecords = int("KAFKA_MAX_POLL_RECORDS", 25),
                maxPollIntervalMillis = int("KAFKA_MAX_POLL_INTERVAL_MS", 300_000),
                pollTimeoutMillis = long("KAFKA_POLL_TIMEOUT_MS", 1_000),
                mongoUri = str("MONGODB_URI", "mongodb://localhost:27017"),
                mongoDatabase = str("MONGODB_DATABASE", "interop"),
                identifierSystemBase = str("FHIR_IDENTIFIER_SYSTEM_BASE", "urn:firmus:identifier"),
                defaultAssigningAuthority = str("DEFAULT_ASSIGNING_AUTHORITY", "UNKNOWN"),
                facilityZone = zone,
                retry = RetryPolicy(
                    attempts = int("WRITE_RETRY_ATTEMPTS", 4),
                    baseBackoffMillis = long("WRITE_RETRY_BASE_BACKOFF_MS", 200),
                    maxBackoffMillis = long("WRITE_RETRY_MAX_BACKOFF_MS", 2_000),
                ),
                shutdownTimeoutMillis = long("SHUTDOWN_TIMEOUT_MS", 20_000),
                httpPort = int("HTTP_PORT", 8081),
                logLevel = logLevel,
                logExternalErrorMessages = bool("LOG_EXTERNAL_ERROR_MESSAGES", false),
            )
            config.validate()
            return config
        }

        /**
         * A client id that identifies the pod, not the service.
         *
         * Kafka reports lag and rebalance events per client id. When every replica shares one,
         * broker-side metrics cannot tell you which replica is the slow one.
         */
        private fun defaultClientId(env: Map<String, String>): String {
            val host = env["HOSTNAME"]?.trim().orEmpty()
            return if (host.isEmpty()) "fhir-mapper" else "fhir-mapper-$host"
        }
    }
}
