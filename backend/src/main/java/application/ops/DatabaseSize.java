package application.ops;

import java.time.Instant;

/** Total PostgreSQL database bytes; failure or absence is never reported as zero. */
public record DatabaseSize(State state, Long bytes, Instant measuredAt) {
    public enum State { AVAILABLE, STALE, UNAVAILABLE }
}
