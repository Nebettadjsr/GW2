package domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Next character birthday (STORY-CHAR-012): the first {@code createdAt + N * 365 days} (N >= 1) that is
 * strictly after {@code now}. Fixed-duration {@link Instant} arithmetic only: no calendar, zone or DST.
 */
public final class CharacterBirthday {
    public static final long INTERVAL_SECONDS = Duration.ofDays(365).getSeconds();

    private CharacterBirthday() {}

    /** Null when creation is unknown, in the future, or the result cannot be represented safely. */
    public static Instant next(Instant createdAt, Instant now) {
        if (createdAt == null || now == null || createdAt.isAfter(now)) return null;
        try {
            long elapsed = Math.subtractExact(now.getEpochSecond(), createdAt.getEpochSecond());
            // createdAt <= now, so elapsed >= 0 and the sub-second parts decide only the exact boundary.
            long intervals = elapsed / INTERVAL_SECONDS + 1;
            Instant candidate = createdAt.plusSeconds(Math.multiplyExact(intervals, INTERVAL_SECONDS));
            if (!candidate.isAfter(now)) { // defensive; sub-second edge
                candidate = candidate.plusSeconds(INTERVAL_SECONDS);
            }
            return candidate;
        } catch (ArithmeticException | java.time.DateTimeException e) {
            return null;
        }
    }
}
