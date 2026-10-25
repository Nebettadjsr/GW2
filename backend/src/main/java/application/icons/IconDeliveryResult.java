package application.icons;

/**
 * What one image request produced (TARGET_ARCHITECTURE.md §12.1). The four cases are exactly the
 * outcomes the endpoint has to distinguish, and none of them carries a filesystem path, an upstream
 * URL or an exception message from a lower layer.
 */
public sealed interface IconDeliveryResult {

    /**
     * Validated image bytes read from the persistent cache.
     *
     * @param etag strong entity tag derived from these bytes, already quoted
     */
    record Delivered(byte[] bytes, String contentType, String etag) implements IconDeliveryResult {}

    /** The route itself is malformed: a 400, decided before any metadata or file access. */
    record InvalidRequest(String reason) implements IconDeliveryResult {}

    /** No such image: unknown item, absent or rejected metadata, obsolete key, or an upstream 404. */
    record NotFound(String reason) implements IconDeliveryResult {}

    /** Temporarily unavailable: a 503 with a finite retry horizon. */
    record Unavailable(String reason, int retryAfterSeconds) implements IconDeliveryResult {}
}
