package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STORY-SYNC-006: the transient-key {@link Gw2ApiClient#getAuth(String, String)} must never print or
 * carry raw upstream bodies or Jackson payload snippets, while still classifying failures by status.
 * Offline: a loopback server stands in for the upstream.
 */
class Gw2ApiClientTransientKeyTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "[]";
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        originalOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() {
        System.setOut(originalOut);
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/account/bank";
    }

    @Test
    void successUsesOnlyTheSuppliedKey() throws Exception {
        body = "[{\"id\":1}]";
        JsonNode node = Gw2ApiClient.getAuth(url(), " transient-key ");
        assertEquals(1, node.size());
        assertEquals("Bearer transient-key", authorization.get());
    }

    @Test
    void failureStatusIsClassifiedWithoutPrintingOrCarryingTheResponseBody() {
        status = 403;
        body = "{\"text\":\"upstream-error-secret transient-key\"}";

        Exception failure = assertThrows(RuntimeException.class, () -> Gw2ApiClient.getAuth(url(), "transient-key"));

        assertTrue(failure.getMessage().contains("HTTP 403"));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("upstream-error-secret"));
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("upstream-error-secret"));
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("Body:"));
    }

    @Test
    void only401And403AreClassifiedAsDefinitiveKeyRejection() {
        for (int rejected : new int[] {401, 403}) {
            status = rejected;
            body = "{\"text\":\"upstream-error-secret\"}";
            Exception failure = assertThrows(Gw2ApiClient.AuthRejectedException.class,
                    () -> Gw2ApiClient.getAuth(url(), "transient-key"));
            assertTrue(Gw2ApiClient.isAuthRejection(failure));
            assertTrue(Gw2ApiClient.isAuthRejection(new Exception("first", new RuntimeException(failure))));
            Exception first = new RuntimeException("other source failed first");
            first.addSuppressed(failure);
            assertTrue(Gw2ApiClient.isAuthRejection(first));
        }
        for (int transientStatus : new int[] {404, 429, 500, 503}) {
            status = transientStatus;
            Exception failure = assertThrows(RuntimeException.class, () -> Gw2ApiClient.getAuth(url(), "transient-key"));
            assertFalse(Gw2ApiClient.isAuthRejection(failure), "HTTP " + transientStatus);
        }
        status = 200;
        body = "upstream-json-secret {not json";
        Exception malformed = assertThrows(java.io.IOException.class, () -> Gw2ApiClient.getAuth(url(), "transient-key"));
        assertFalse(Gw2ApiClient.isAuthRejection(malformed));
    }

    @Test
    void malformedJsonDoesNotQuoteThePayloadInTheErrorOrOutput() {
        body = "upstream-json-secret {not json";

        Exception failure = assertThrows(java.io.IOException.class, () -> Gw2ApiClient.getAuth(url(), "transient-key"));

        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("upstream-json-secret"));
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("upstream-json-secret"));
    }

    @Test
    void blankKeyIsRefusedWithoutAnyRequest() {
        assertThrows(IllegalStateException.class, () -> Gw2ApiClient.getAuth(url(), " "));
        assertNull(authorization.get());
    }
}
