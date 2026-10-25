package infra.icons;

import java.time.Duration;

/**
 * The finite protective bounds TARGET_ARCHITECTURE.md §12.1 requires the implementation to choose and
 * document. They are limits that keep one cache miss from consuming unbounded time, memory, sockets
 * or disk - not measured latencies, and not a claim about how fast the upstream host answers.
 *
 * <p>The values are deliberately generous relative to a ~10 KB game icon: an icon that needs more
 * than {@link #MAX_RESPONSE_BYTES} or longer than {@link #RESPONSE_TIMEOUT} is far likelier to be an
 * error page or a stalled connection than a real image.
 */
public final class IconCacheBounds {

    /** TCP/TLS connect deadline for one upstream image request. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** End-to-end deadline for one upstream image response, connect included. */
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    /** Response bytes read before the body is abandoned as oversized. */
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Decoded pixels (width x height) accepted, so a small file cannot decode into a huge raster. */
    public static final long MAX_DECODED_PIXELS = 4_000_000L;

    /** Upstream downloads allowed to run at the same time across the whole process. */
    public static final int MAX_CONCURRENT_DOWNLOADS = 4;

    /** Requests allowed to be handling a miss at the same time; further ones fail fast as busy. */
    public static final int MAX_WAITING_MISSES = 32;

    /** How long a request waits for a coordination slot or a download slot before giving up. */
    public static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long a failed key stops further upstream attempts. In-process and short by design: it must
     * expire on its own and it never hides a valid disk hit, which is rechecked first.
     */
    public static final Duration FAILURE_SUPPRESSION = Duration.ofSeconds(30);

    /** The finite {@code Retry-After} a 503 carries; same horizon as {@link #FAILURE_SUPPRESSION}. */
    public static final int RETRY_AFTER_SECONDS = 30;

    private IconCacheBounds() {}
}
