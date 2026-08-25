package ai.firmus.interop.fhir

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * `/healthz`, `/readyz` and `/metrics`, on the JDK's own HTTP server.
 *
 * A Kafka consumer has no inbound traffic, which makes it the easiest kind of service to leave
 * running dead: the process is up, the container is "healthy" by any check that only looks at the
 * PID, and the loop inside it has been stuck on a socket for an hour. So the probe asks the loop
 * itself.
 *
 * Liveness and readiness are separated on purpose, because the orchestrator does very different
 * things with them:
 *
 *  - `/healthz` fails only when the consume loop is no longer running. That is unrecoverable
 *    in-process and restarting is the right response.
 *  - `/readyz` also fails when the loop has not completed a `poll()` recently. A stalled poll
 *    usually means a broker problem, which a restart does not fix and which restarting every
 *    replica actively makes worse. Failing readiness alone marks the pod as not-serving without
 *    killing it.
 *
 * `com.sun.net.httpserver` rather than a framework: three endpoints returning fixed strings do not
 * justify a servlet container, its thread pool, or its CVE feed inside a HIPAA-scoped image.
 */
class HealthServer(
    port: Int,
    private val metrics: Metrics,
    private val log: Logger,
    private val isRunning: () -> Boolean,
    private val lastPollAt: () -> Instant,
    private val stallThreshold: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), BACKLOG)

    init {
        server.createContext("/healthz") { exchange ->
            if (isRunning()) respond(exchange, 200, "ok\n") else respond(exchange, 503, "consumer stopped\n")
        }
        server.createContext("/readyz") { exchange ->
            val since = Duration.between(lastPollAt(), clock())
            when {
                !isRunning() -> respond(exchange, 503, "consumer stopped\n")
                since > stallThreshold -> respond(exchange, 503, "no poll for ${since.toSeconds()}s\n")
                else -> respond(exchange, 200, "ready\n")
            }
        }
        server.createContext("/metrics") { exchange ->
            respond(exchange, 200, metrics.render(), "text/plain; version=0.0.4; charset=utf-8")
        }
        // A null executor runs handlers on the dispatcher thread. These handlers do no I/O and no
        // blocking work, so a pool would add threads and a shutdown path for no benefit.
        server.executor = null
    }

    fun start() {
        server.start()
        log.info("health.listening", "port" to server.address.port)
    }

    fun stop(gracePeriod: Duration) {
        server.stop(gracePeriod.toSeconds().toInt())
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: IOException) {
            // A probe that hung up mid-response is not an event worth an error log; a health check
            // that logged at ERROR every time kubelet timed out would bury the logs that matter.
            log.debug("health.response_failed", "errorType" to e.javaClass.simpleName)
        } finally {
            exchange.close()
        }
    }

    private companion object {
        const val BACKLOG = 4
    }
}
