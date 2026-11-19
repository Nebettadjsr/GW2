package api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded acquisition against a controlled loopback server: no external network. */
class PublicApiTransportTest {
    private HttpServer server;
    private final CountDownLatch stall = new CountDownLatch(1);
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/small", ex -> reply(ex, 200, "{\"ok\":true}".getBytes()));
        server.createContext("/big", ex -> reply(ex, 200, new byte[200_000]));
        server.createContext("/limited", ex -> reply(ex, 429, "{}".getBytes()));
        server.createContext("/boom", ex -> reply(ex, 503, "{}".getBytes()));
        server.createContext("/stall", ex -> {
            ex.sendResponseHeaders(200, 1_000_000); // headers, then no body
            try { stall.await(); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { stall.countDown(); server.stop(0); }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private PublicApiTransport transport(int max, Duration bound) {
        return PublicApiTransport.bounded(HttpClient.newHttpClient(), max, bound);
    }

    @Test
    void withinBoundBodyIsReturned() throws Exception {
        var r = transport(1000, Duration.ofSeconds(5)).get(base + "/small");
        assertEquals(200, r.status());
        assertEquals("{\"ok\":true}", r.body());
    }

    @Test
    void oversizeBodyIsCancelledWhileReadingAndReportedWithoutABody() throws Exception {
        var r = transport(10_000, Duration.ofSeconds(5)).get(base + "/big");
        assertEquals(200, r.status());
        assertNull(r.body());
    }

    @Test
    void bodyThatStallsAfterHeadersTimesOutInsteadOfWaitingForever() {
        long t0 = System.nanoTime();
        assertThrows(HttpTimeoutException.class, () -> transport(1000, Duration.ofMillis(500)).get(base + "/stall"));
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 5);
    }

    @Test
    void rateLimitAndServerErrorKeepTheirStatusForClassification() throws Exception {
        assertEquals(429, transport(1000, Duration.ofSeconds(5)).get(base + "/limited").status());
        assertEquals(503, transport(1000, Duration.ofSeconds(5)).get(base + "/boom").status());
    }

    @Test
    void connectionFailureIsAnIoExceptionNotAShapeProblem() throws Exception {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThrows(IOException.class, () -> transport(1000, Duration.ofSeconds(2))
                .get("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port + "/small"));
    }
}
