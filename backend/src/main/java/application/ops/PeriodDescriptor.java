package application.ops;

import java.time.Instant;

/**
 * Safe metadata of one downloadable UTC period (STORY-OPS-003). The {@code selectionId} is opaque: only the
 * period store resolves it, and consumers never submit paths or arbitrary ranges. Closure and coverage are
 * separate: a CLOSED period is not proof of continuous monitoring or service uptime, and missing minutes mean
 * only "no stored included application request activity".
 */
public record PeriodDescriptor(String selectionId, Kind kind, Instant fromUtc, Instant toUtc, State state,
                               Coverage coverage, boolean truncated, boolean pruned, long retainedBytes,
                               String filename) {
    public enum Kind { DAY, WEEK }

    /** OPEN: the UTC period has not ended. CLOSED: its UTC boundary has passed. */
    public enum State { OPEN, CLOSED }

    /** FULL only for a closed period with no known truncation, pruning, persistence or continuity loss. */
    public enum Coverage { FULL, PARTIAL }
}
