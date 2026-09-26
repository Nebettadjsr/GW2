package infra.icons;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream image adapter against a controlled local HTTP fixture (TARGET_ARCHITECTURE.md §12.1,
 * TEST_STRATEGY.md §31.2): which responses may become bytes, which may not, and what the request
 * itself is allowed to contain.
 *
 * <p>The fixture binds {@code 127.0.0.1} explicitly and every request is served by it, so no test here
 * can reach ArenaNet, the GW2 API or any other real host (tasks/lessons.md: a local check must prove it
 * is talking to its own server).
 */
class HttpIconImageFetcherTest {

    private HttpServer server;
    private HttpIconImageFetcher fetcher;
    private final List<Map<String, List<String>>> receivedHeaders = new CopyOnWriteArrayList<>();

    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private volatile String location;

    @BeforeEach
    void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file", this::respond);
        server.start();

        fetcher = new HttpIconImageFetcher(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(IconCacheBounds.CONNECT_TIMEOUT)
                .build());
    }

    @AfterEach
    void stopFixture() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedHeaders.add(exchange.getRequestHeaders());

        if (location != null) exchange.getResponseHeaders().add("Location", location);

        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** A source pointing at the fixture; the canonical-source policy itself is tested separately. */
    private IconSource source(String extension) {
        return new IconSource(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/file/ABCD/1." + extension,
                "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472",
                extension);
    }

    // ---------- accepted responses ----------

    @Test
    void returnsTheBytesOfAValidatedImage() {
        status = 200;
        body = TestImages.png();

        IconFetchResult result = fetcher.fetch(source("png"));

        assertArrayEquals(body, assertInstanceOf(IconFetchResult.Fetched.class, result).bytes());
    }

    @Test
    void sendsNoApiKeyCookieOrAuthorizationHeader() {
        status = 200;
        body = TestImages.png();

        fetcher.fetch(source("png"));

        Map<String, List<String>> headers = receivedHeaders.get(0);
        assertTrue(headers.get("Authorization") == null, "no application authorization may leave");
        assertTrue(headers.get("Cookie") == null, "no cookie may leave");
        assertTrue(headers.get("X-Api-Key") == null, "no GW2 API key may leave");
        assertEquals(null, headers.get("Access-Token"));
    }

    // ---------- refused responses ----------

    @Test
    void anUpstream404IsAMissingImageRatherThanAFailure() {
        status = 404;
        body = TestImages.htmlErrorBody();

        assertInstanceOf(IconFetchResult.UpstreamMissing.class, fetcher.fetch(source("png")));
    }

    @Test
    void anyOtherNonSuccessStatusIsATemporaryFailure() {
        status = 500;
        body = new byte[0];

        assertInstanceOf(IconFetchResult.TemporaryFailure.class, fetcher.fetch(source("png")));
    }

    @Test
    void aRedirectIsNotFollowed() {
        status = 302;
        location = "https://cdn.example.com/elsewhere.png";
        body = new byte[0];

        IconFetchResult result = fetcher.fetch(source("png"));

        assertInstanceOf(IconFetchResult.TemporaryFailure.class, result);
        assertEquals(1, receivedHeaders.size(), "exactly one request: the redirect must not be chased");
    }

    @Test
    void anHtmlBodyUnderA200IsARejectedImage() {
        status = 200;
        body = TestImages.htmlErrorBody();

        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcher.fetch(source("png")));
    }

    @Test
    void aTruncatedImageIsARejectedImage() {
        status = 200;
        body = TestImages.truncatedPng();

        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcher.fetch(source("png")));
    }

    @Test
    void anImageWhoseFormatDisagreesWithTheSourceExtensionIsRejected() {
        status = 200;
        body = TestImages.png();

        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcher.fetch(source("jpg")));
    }

    @Test
    void aBodyBeyondTheByteBoundIsRejectedWithoutBeingKept() {
        status = 200;
        body = new byte[IconCacheBounds.MAX_RESPONSE_BYTES + 1_024];
        System.arraycopy(TestImages.png(), 0, body, 0, TestImages.png().length);

        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcher.fetch(source("png")));
    }

    @Test
    void anImageBeyondTheDecodedPixelBoundIsRejected() {
        status = 200;
        body = TestImages.oversizedPixelsPng();

        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcher.fetch(source("png")));
    }

    @Test
    void anUnreachableHostIsATemporaryFailureRatherThanAnException() {
        // A port nothing is listening on: the connect deadline decides, and the adapter reports rather
        // than throws, so one bad source cannot break a caller's loop.
        IconSource unreachable = new IconSource(
                "http://127.0.0.1:1/file/ABCD/1.png",
                "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472",
                "png");

        assertInstanceOf(IconFetchResult.TemporaryFailure.class, fetcher.fetch(unreachable));
    }
}
