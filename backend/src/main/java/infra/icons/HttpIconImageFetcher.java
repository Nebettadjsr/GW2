package infra.icons;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The upstream image adapter (TARGET_ARCHITECTURE.md §12.1): one plain HTTPS GET of the matched
 * canonical URL, under the finite bounds of {@link IconCacheBounds}, with the response validated as a
 * real image before anybody may publish it.
 *
 * <p>Deliberate omissions, all of them part of the contract rather than oversights: redirects are
 * disabled, no header is set at all (so no API key, cookie, application authorization or account data
 * can leave), the URL comes from {@link IconSource} rather than from any caller, and the body is read
 * through a hard byte ceiling instead of into an unbounded array. This is an asset fetcher, not a
 * proxy.
 *
 * <p>Separate from {@code api.Gw2ApiClient}, which talks to the GW2 JSON API with the account's key;
 * an image fetch must share none of that.
 *
 * <p>Thread-safe: {@link HttpClient} is, and the fetcher holds nothing else.
 */
public final class HttpIconImageFetcher implements IconImageFetcher {

    private final HttpClient client;

    /**
     * The production client. It routes through the JVM's own configured proxy selector
     * ({@code https.proxyHost}/{@code https.proxyPort}/{@code http.nonProxyHosts}) rather than the
     * builder's default of no proxy at all, so where egress is a deployment concern it is
     * configuration and not a code change (§15). With nothing configured the selector chooses a direct
     * connection, which is what every run so far has made.
     *
     * <p>It is also what makes an upstream image fetch externally observable: a check can route this
     * client through a counting proxy and so <em>count</em> upstream requests instead of inferring
     * their absence from a rendered picture (§12.1's verification list).
     */
    public HttpIconImageFetcher() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(IconCacheBounds.CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build());
    }

    /** Seam for tests, which point a client at a local fixture server (TEST_STRATEGY.md §31.2). */
    public HttpIconImageFetcher(HttpClient client) {
        this.client = client;
    }

    @Override
    public IconFetchResult fetch(IconSource source) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.canonicalUrl()))
                .timeout(IconCacheBounds.RESPONSE_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return readValidated(response, source.extension());
        } catch (IOException transportFailure) {
            return new IconFetchResult.TemporaryFailure(
                    "upstream request failed: " + transportFailure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new IconFetchResult.TemporaryFailure("upstream request was interrupted");
        }
    }

    private static IconFetchResult readValidated(HttpResponse<InputStream> response, String extension)
            throws IOException {

        int status = response.statusCode();
        if (status == 404) return new IconFetchResult.UpstreamMissing();
        if (status != 200) {
            // Includes 3xx: redirects are not followed, so a moved image is an upstream problem here
            // rather than something this endpoint chases or hands to the browser.
            return new IconFetchResult.TemporaryFailure("upstream status " + status);
        }

        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = readBounded(body);
        }

        if (bytes == null) {
            return new IconFetchResult.RejectedImage(
                    "response exceeded " + IconCacheBounds.MAX_RESPONSE_BYTES + " bytes");
        }

        IconImageBytes.Validation validation = IconImageBytes.validate(bytes, extension);
        if (!validation.valid()) return new IconFetchResult.RejectedImage(validation.reason());

        return new IconFetchResult.Fetched(bytes);
    }

    /** The bytes, or null when the body is larger than the accepted maximum. */
    private static byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(IconCacheBounds.MAX_RESPONSE_BYTES + 1);
        return bytes.length > IconCacheBounds.MAX_RESPONSE_BYTES ? null : bytes;
    }
}
