package infra.icons;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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

    /**
     * The production constructor's client, unlike the injected one above, routes through the JVM's own
     * proxy selector. Asserted through behaviour rather than through the field: a configured proxy has
     * to be the host actually contacted, which is also what lets a live check count upstream image
     * requests instead of inferring them (§12.1).
     */
    @Test
    void theProductionClientHonoursTheJvmsConfiguredProxy() {
        status = 200;
        body = TestImages.png();
        String previousHost = System.getProperty("http.proxyHost");
        String previousPort = System.getProperty("http.proxyPort");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(server.getAddress().getPort()));
        try {
            // A host that cannot resolve: only the proxy can answer this, so the fixture receiving it
            // is the proof. With no proxy the same fetch could not reach any server at all.
            IconSource offMachine = new IconSource(
                    "http://render.guildwars2.invalid/file/ABCD/1.png",
                    "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472",
                    "png");

            IconFetchResult result = new HttpIconImageFetcher().fetch(offMachine);

            assertArrayEquals(
                    TestImages.png(), assertInstanceOf(IconFetchResult.Fetched.class, result).bytes());
        } finally {
            restore("http.proxyHost", previousHost);
            restore("http.proxyPort", previousPort);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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

    // ---------- response body lifecycle ----------

    /** An input stream that records how often it was closed, optionally failing on read or close. */
    private static final class TrackedBody extends InputStream {
        private final ByteArrayInputStream delegate;
        private final boolean failOnRead;
        private final boolean failOnClose;
        int closeCount;

        TrackedBody(byte[] bytes, boolean failOnRead, boolean failOnClose) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.failOnRead = failOnRead;
            this.failOnClose = failOnClose;
        }

        @Override
        public int read() throws IOException {
            if (failOnRead) throw new IOException("simulated read failure");
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (failOnRead) throw new IOException("simulated read failure");
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (failOnClose) throw new IOException("simulated close failure");
        }
    }

    /** A client that answers every request with one canned response and never touches the network. */
    private static HttpIconImageFetcher fetcherAnswering(int statusCode, TrackedBody body) {
        HttpResponse<InputStream> response = new HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public InputStream body() { return body; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://render.guildwars2.com/file/ABCD/1.png"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        HttpClient client = new HttpClient() {
            @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
            @Override public SSLContext sslContext() { return null; }
            @Override public SSLParameters sslParameters() { return null; }
            @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public Optional<Executor> executor() { return Optional.empty(); }

            @Override
            @SuppressWarnings("unchecked")
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                return (HttpResponse<T>) response;
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest request,
                    HttpResponse.BodyHandler<T> handler,
                    HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                throw new UnsupportedOperationException();
            }
        };
        return new HttpIconImageFetcher(client);
    }

    private static IconSource pngSource() {
        return new IconSource(
                "https://render.guildwars2.com/file/ABCD/1.png",
                "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472",
                "png");
    }

    @Test
    void aValidImageResponseBodyIsClosedExactlyOnce() {
        TrackedBody body = new TrackedBody(TestImages.png(), false, false);

        IconFetchResult result = fetcherAnswering(200, body).fetch(pngSource());

        assertInstanceOf(IconFetchResult.Fetched.class, result);
        assertEquals(1, body.closeCount);
    }

    @Test
    void a404ResponseBodyIsClosedAndStillMeansMissing() {
        TrackedBody body = new TrackedBody(TestImages.htmlErrorBody(), false, false);

        IconFetchResult result = fetcherAnswering(404, body).fetch(pngSource());

        assertInstanceOf(IconFetchResult.UpstreamMissing.class, result);
        assertEquals(1, body.closeCount);
    }

    @Test
    void redirectAndServerErrorResponseBodiesAreClosedAndStillTemporaryFailures() {
        for (int statusCode : new int[] {302, 500, 503}) {
            TrackedBody body = new TrackedBody(new byte[] {1, 2, 3}, false, false);

            IconFetchResult result = fetcherAnswering(statusCode, body).fetch(pngSource());

            assertInstanceOf(IconFetchResult.TemporaryFailure.class, result, "status " + statusCode);
            assertEquals(1, body.closeCount, "status " + statusCode);
        }
    }

    @Test
    void rejectedAndOversizedResponseBodiesAreClosed() {
        TrackedBody html = new TrackedBody(TestImages.htmlErrorBody(), false, false);
        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcherAnswering(200, html).fetch(pngSource()));
        assertEquals(1, html.closeCount);

        TrackedBody huge = new TrackedBody(new byte[IconCacheBounds.MAX_RESPONSE_BYTES + 1_024], false, false);
        assertInstanceOf(IconFetchResult.RejectedImage.class, fetcherAnswering(200, huge).fetch(pngSource()));
        assertEquals(1, huge.closeCount);
    }

    @Test
    void aBodyThatFailsWhileReadingIsClosedAndReportedAsTemporaryFailure() {
        TrackedBody body = new TrackedBody(TestImages.png(), true, false);

        IconFetchResult result = fetcherAnswering(200, body).fetch(pngSource());

        assertInstanceOf(IconFetchResult.TemporaryFailure.class, result);
        assertEquals(1, body.closeCount);
    }

    @Test
    void aFailureToCloseAnUnwantedStatusBodyDoesNotChangeTheStatusOutcome() {
        TrackedBody missing = new TrackedBody(new byte[] {1}, false, true);
        assertInstanceOf(IconFetchResult.UpstreamMissing.class, fetcherAnswering(404, missing).fetch(pngSource()));
        assertEquals(1, missing.closeCount);

        TrackedBody failing = new TrackedBody(new byte[] {1}, false, true);
        assertInstanceOf(IconFetchResult.TemporaryFailure.class, fetcherAnswering(500, failing).fetch(pngSource()));
        assertEquals(1, failing.closeCount);
    }
}
