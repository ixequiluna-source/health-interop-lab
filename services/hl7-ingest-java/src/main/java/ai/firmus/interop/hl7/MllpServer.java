package ai.firmus.interop.hl7;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A concurrent MLLP listener.
 *
 * <p>Connections are long-lived by convention in HL7 — a sending system opens one socket and
 * streams messages through it for days — so each connection is handled by its own virtual
 * thread and loops until the peer closes. A read timeout bounds half-open sockets, which
 * otherwise accumulate after network equipment silently drops idle connections.
 */
public final class MllpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MllpServer.class.getName());

    private final IngestHandler handler;
    private final int readTimeoutMillis;
    private final ServerSocket serverSocket;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread acceptLoop;

    public MllpServer(int port, IngestHandler handler, int readTimeoutMillis) throws IOException {
        this.handler = handler;
        this.readTimeoutMillis = readTimeoutMillis;
        this.serverSocket = new ServerSocket(port);
    }

    /** The bound port; useful when the server was started on port 0 in tests. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        acceptLoop =
                Thread.ofPlatform()
                        .name("mllp-accept")
                        .start(
                                () -> {
                                    while (running.get()) {
                                        try {
                                            Socket socket = serverSocket.accept();
                                            connections.submit(() -> serve(socket));
                                        } catch (IOException e) {
                                            if (running.get()) {
                                                LOG.log(Level.WARNING, "Accept failed", e);
                                            }
                                            return;
                                        }
                                    }
                                });
        LOG.log(Level.INFO, "MLLP listener started on port {0}", port());
    }

    private void serve(Socket socket) {
        try (socket;
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(readTimeoutMillis);
            socket.setTcpNoDelay(true);
            while (running.get()) {
                Optional<String> frame = MllpCodec.readFrame(in, StandardCharsets.UTF_8);
                if (frame.isEmpty()) {
                    return;
                }
                String ack;
                try {
                    ack = handler.handle(frame.get());
                } catch (IngestHandler.RetryableFailure e) {
                    // Deliberately no ACK: dropping the connection makes the sender retry.
                    LOG.log(Level.SEVERE, "Dropping connection without ACK: {0}", e.getMessage());
                    return;
                }
                MllpCodec.writeFrame(out, ack, StandardCharsets.UTF_8);
            }
        } catch (Hl7ParseException e) {
            LOG.log(Level.WARNING, "Malformed MLLP stream, closing connection: {0}", e.getMessage());
        } catch (IOException e) {
            LOG.log(Level.FINE, "Connection closed", e);
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        connections.shutdown();
        try {
            if (!connections.awaitTermination(5, TimeUnit.SECONDS)) {
                connections.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptLoop != null) {
            acceptLoop.interrupt();
        }
    }
}
