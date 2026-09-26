package infra.icons;

/**
 * What one upstream image request produced. The four cases are kept apart because §12.1 maps them to
 * different outcomes: a missing image is a 404, a rejected or temporarily failing one a 503, and only
 * {@link Fetched} bytes may ever be published.
 */
public sealed interface IconFetchResult {

    /** A complete, validated image matching the requested extension. */
    record Fetched(byte[] bytes) implements IconFetchResult {}

    /** The upstream host answered 404: this source does not exist. */
    record UpstreamMissing() implements IconFetchResult {}

    /** The response arrived but is not a usable image (HTML body, wrong format, oversized, truncated). */
    record RejectedImage(String reason) implements IconFetchResult {}

    /** The request could not be completed: timeout, connection failure, redirect or any non-200 status. */
    record TemporaryFailure(String reason) implements IconFetchResult {}
}
