package ai.firmus.interop.hl7;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Entry point.
 *
 * <p>Configuration comes from the environment so the same image runs in every environment and
 * no secret or endpoint is baked into the artifact — the deployment concern that SOC 2 CC6.1
 * and the Kubernetes manifests in {@code infra/k8s} are both about.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        int mllpPort = envInt("MLLP_PORT", 2575);
        int httpPort = envInt("HTTP_PORT", 8080);
        int readTimeout = envInt("MLLP_READ_TIMEOUT_MS", 300_000);
        String bootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "");
        String topic = env("KAFKA_TOPIC", "clinical.admissions.v1");
        String app = env("RECEIVING_APPLICATION", "INTEROP_LAB");
        String facility = env("RECEIVING_FACILITY", "FIRMUS");

        EventSink sink =
                bootstrap.isBlank()
                        ? new InMemoryEventSink()
                        : new KafkaEventSink(bootstrap, topic, Duration.ofSeconds(30));
        if (bootstrap.isBlank()) {
            LOG.warning("KAFKA_BOOTSTRAP_SERVERS is unset; running with an in-memory sink");
        }

        Clock clock = Clock.systemUTC();
        IngestHandler handler =
                new IngestHandler(new AdtMapper(clock), new AckBuilder(app, facility, clock), sink);

        MllpServer mllp = new MllpServer(mllpPort, handler, readTimeout);
        mllp.start();
        HttpServer http = startHealthEndpoint(httpPort, handler);

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    // Ordered shutdown: stop accepting, drain, then flush Kafka.
                                    try {
                                        mllp.close();
                                    } catch (IOException ignored) {
                                        // Already closing.
                                    }
                                    http.stop(5);
                                    sink.close();
                                    LOG.info("Shutdown complete");
                                }));

        Thread.currentThread().join();
    }

    private static HttpServer startHealthEndpoint(int port, IngestHandler handler)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(
                "/healthz",
                exchange -> respond(exchange.getResponseBody(), exchange, "{\"status\":\"ok\"}"));
        server.createContext(
                "/metrics",
                exchange -> {
                    String body =
                            """
                            # HELP hl7_messages_total Messages by outcome.
                            # TYPE hl7_messages_total counter
                            hl7_messages_total{outcome="accepted"} %d
                            hl7_messages_total{outcome="rejected"} %d
                            hl7_messages_total{outcome="failed"} %d
                            """
                                    .formatted(
                                            handler.acceptedCount(),
                                            handler.rejectedCount(),
                                            handler.failedCount());
                    respond(exchange.getResponseBody(), exchange, body);
                });
        server.start();
        LOG.info("Health and metrics endpoint on port " + port);
        return server;
    }

    private static void respond(
            OutputStream body, com.sun.net.httpserver.HttpExchange exchange, String payload)
            throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (body) {
            body.write(bytes);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envInt(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }
}
