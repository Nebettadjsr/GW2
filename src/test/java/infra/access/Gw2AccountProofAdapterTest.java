package infra.access;

import application.access.AccountProof;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import application.access.ProofUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** The real HTTP adapter against a loopback stub: no ArenaNet request, no real key. */
class Gw2AccountProofAdapterTest {
    private static final String GUID = "AAAAAAAA-1111-2222-3333-BBBBBBBBBBBB";
    private static final String KEY = "transient-test-key";

    private HttpServer server;
    private final Map<String, Integer> status = new ConcurrentHashMap<>();
    private final Map<String, String> body = new ConcurrentHashMap<>();
    private final Map<String, String> retryAfter = new ConcurrentHashMap<>();
    private final List<String> seenAuth = new ArrayList<>();
    private final List<String> seenUris = new ArrayList<>();
    private Gw2AccountProofAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (seenAuth) {
                seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                seenUris.add(exchange.getRequestURI().toString());
            }
            byte[] out = body.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            if (retryAfter.containsKey(path)) exchange.getResponseHeaders().add("Retry-After", retryAfter.get(path));
            exchange.sendResponseHeaders(status.getOrDefault(path, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        adapter = new Gw2AccountProofAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                Clock.systemUTC());
        status.put("/v2/account", 200);
        body.put("/v2/account", "{\"id\":\"" + GUID + "\"}");
        status.put("/v2/tokeninfo", 200);
        body.put("/v2/tokeninfo", "{\"permissions\":[\"account\"]}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void provesBothEndpointsWithSameKeyInHeaderOnly() throws Exception {
        AccountProof proof = adapter.prove("  " + KEY + " ");
        assertEquals(GUID, proof.accountId());
        assertEquals(List.of("Bearer " + KEY, "Bearer " + KEY), seenAuth);
        assertEquals(List.of("/v2/account", "/v2/tokeninfo"), seenUris);
    }

    @Test
    void rejectedCredentialIsDefinitiveAndBlankKeyNeverReachesUpstream() {
        status.put("/v2/account", 401);
        assertThrows(InvalidCredentialException.class, () -> adapter.prove(KEY));
        assertThrows(InvalidCredentialException.class, () -> adapter.prove(" "));
    }

    @Test
    void tokenInfoRejectionAfterAccountSuccessIsStillInvalid() {
        status.put("/v2/tokeninfo", 403);
        assertThrows(InvalidCredentialException.class, () -> adapter.prove(KEY));
    }

    @Test
    void rateLimitIsRetryableWithBoundedGuidance() {
        status.put("/v2/account", 429);
        retryAfter.put("/v2/account", "99999");
        ProofUnavailableException e = assertThrows(ProofUnavailableException.class, () -> adapter.prove(KEY));
        assertEquals(ProofUnavailableException.MAX_RETRY_AFTER, e.retryAfter());
    }

    @Test
    void upstreamOutageIsRetryableAndUnreachableUpstreamIsToo() {
        status.put("/v2/tokeninfo", 503);
        assertThrows(ProofUnavailableException.class, () -> adapter.prove(KEY));
        server.stop(0);
        ProofUnavailableException e = assertThrows(ProofUnavailableException.class, () -> adapter.prove(KEY));
        assertFalse(e.getMessage().contains(KEY));
        assertTrue(e.retryAfter().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void nonJsonAndUnexpectedStatusFailClosed() {
        body.put("/v2/account", "<html>");
        assertThrows(MalformedProofException.class, () -> adapter.prove(KEY));
        body.put("/v2/account", "{\"id\":\"" + GUID + "\"}");
        status.put("/v2/tokeninfo", 302);
        assertThrows(MalformedProofException.class, () -> adapter.prove(KEY));
    }
}
