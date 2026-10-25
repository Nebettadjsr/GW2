package application.access;

import java.time.Duration;

/**
 * Upstream validation could not be completed - transport failure, server error or rate limiting
 * (STORY-API-010, TARGET_ARCHITECTURE.md §8.1). Retryable: it maps to 503 with bounded guidance and
 * never revokes a session, so an already validated lease keeps authorizing cached reads through an
 * outage while new proof and renewal fail.
 */
public class ProofUnavailableException extends Exception {

    /** Bounded guidance for the caller; the upstream value is clamped, never echoed unbounded. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

    private final Duration retryAfter;

    public ProofUnavailableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = clamp(retryAfter);
    }

    public ProofUnavailableException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = clamp(retryAfter);
    }

    /** Always a usable positive bound, so a caller never has to invent one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    private static Duration clamp(Duration requested) {
        if (requested == null || requested.isNegative() || requested.isZero()) return Duration.ofSeconds(5);
        return requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested;
    }
}
