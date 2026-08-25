package ai.firmus.interop.fhir

import com.mongodb.MongoClientSettings
import com.mongodb.ConnectionString
import com.mongodb.client.MongoClients
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Wiring and lifecycle.
 *
 * Everything above this file is a value or a collaborator that can be constructed in a test.
 * This is the only place that reads the environment, opens sockets or installs a signal handler,
 * which is what keeps the rest of the service testable without a broker or a database.
 */
fun main() {
    // Library logging goes to stderr at WARN and stays out of the structured stdout stream, which
    // is reserved for this service's own JSON lines. Set before any logger is instantiated,
    // because slf4j-simple reads these once, at class initialisation.
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")

    val bootLog = Logger("boot")

    val config = try {
        Config.fromEnvironment()
    } catch (e: ConfigException) {
        // Exit before anything is open. A service that starts with a configuration that cannot be
        // correct and discovers it later has already begun writing something wrong.
        bootLog.error("config.invalid", e)
        exitProcess(EXIT_CONFIG)
    }

    val log = Logger(
        component = "fhir-mapper",
        minLevel = config.logLevel,
        includeExternalErrorMessages = config.logExternalErrorMessages,
    )
    log.info(
        "service.starting",
        "topic" to config.kafkaTopic,
        "dlqTopic" to config.kafkaDlqTopic,
        "groupId" to config.kafkaGroupId,
        "database" to config.mongoDatabase,
        "facilityZone" to config.facilityZone.id,
        "maxPollRecords" to config.maxPollRecords,
    )

    val metrics = Metrics()

    val mongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(config.mongoUri))
            .applicationName(config.kafkaClientId)
            .build(),
    )
    val consumer = KafkaClients.consumer(config)
    val dlqProducer = KafkaClients.deadLetterProducer(config)

    val writer = MongoWriter(mongoDatabase(mongoClient, config.mongoDatabase), metrics, log.withComponent("mongo"))
    val processor = AdmissionProcessor(
        mapper = FhirMapper(config),
        store = writer,
        metrics = metrics,
        log = log.withComponent("processor"),
        retry = config.retry,
    )
    val admissionConsumer = AdmissionConsumer(
        consumer = consumer,
        processor = processor,
        deadLetters = KafkaDeadLetterSink(dlqProducer, config.kafkaDlqTopic, log.withComponent("dlq")),
        metrics = metrics,
        log = log.withComponent("consumer"),
        topic = config.kafkaTopic,
        pollTimeout = Duration.ofMillis(config.pollTimeoutMillis),
        shutdownTimeout = Duration.ofMillis(config.shutdownTimeoutMillis),
    )

    val health = HealthServer(
        port = config.httpPort,
        metrics = metrics,
        log = log.withComponent("health"),
        isRunning = admissionConsumer::isRunning,
        lastPollAt = admissionConsumer::lastPollAt,
        // Twice the poll interval: one missed cycle is a slow batch, two is a stall. Anything
        // tighter turns an ordinary GC pause into a readiness flap.
        stallThreshold = Duration.ofMillis(config.maxPollIntervalMillis.toLong() * 2),
    )
    health.start()

    val stopped = CountDownLatch(1)

    // SIGTERM arrives as a JVM shutdown hook. The hook thread cannot touch the consumer directly —
    // KafkaConsumer is not thread-safe, and calling close() from here while the loop is mid-poll is
    // undefined. wakeup() is the one method that is safe to call from another thread, and it exists
    // for exactly this: it aborts the blocking poll so the loop can unwind and close the consumer
    // itself, on its own thread.
    //
    // The hook then waits. Returning immediately would let the JVM exit while the loop is still
    // committing, which loses the final commit and reprocesses that batch on the next start — and,
    // worse, skips the LeaveGroup, so the group stalls for a full session timeout on every deploy.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("shutdown.signal_received")
            admissionConsumer.stop()
            if (!stopped.await(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log.warn("shutdown.timed_out", "timeoutMs" to config.shutdownTimeoutMillis)
            }
            health.stop(Duration.ofSeconds(1))
            dlqProducer.close(Duration.ofMillis(config.shutdownTimeoutMillis))
            mongoClient.close()
            log.info("shutdown.complete")
        },
        "shutdown",
    )

    try {
        writer.ensureIndexes()
        admissionConsumer.run()
        log.info("service.stopped")
    } catch (e: RuntimeException) {
        // The loop only exits abnormally on something it could not classify as retryable or
        // dead-letterable. Exiting non-zero is deliberate: the offsets for the in-flight batch were
        // never committed, so a restart replays them, and a crash-looping pod is visible in a way
        // that a process quietly consuming nothing is not.
        log.error("service.fatal", e)
        stopped.countDown()
        exitProcess(EXIT_FATAL)
    } finally {
        stopped.countDown()
    }
}

private const val EXIT_CONFIG = 78 // EX_CONFIG, sysexits.h
private const val EXIT_FATAL = 70 // EX_SOFTWARE, sysexits.h
