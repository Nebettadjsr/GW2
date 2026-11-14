package application.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Distinct verified accounts with eligible foreground activity in the actual trailing five minutes
 * (STORY-OPS-001 section 1): one capped in-memory map of account GUID to latest activity. Identities live
 * only here; nothing derived from them is ever returned or persisted.
 */
public final class ForegroundActivityTracker {
    public static final Duration WINDOW = Duration.ofMinutes(5);
    public static final int DEFAULT_CAPACITY = 10_000;
    /** Wall-clock vs monotonic disagreement beyond this makes the window unreliable. */
    static final Duration DISCONTINUITY_TOLERANCE = Duration.ofSeconds(5);

    private final Clock clock;
    private final LongSupplier monotonicNanos;
    private final int capacity;
    private final Map<UUID, Instant> latest = new HashMap<>();
    private Instant continuousSince;
    private Instant lastWall;
    private long lastNanos;
    /** Latest instant at which a new identity was refused for capacity; omitted activity ages out from it. */
    private Instant lastOmission;

    public ForegroundActivityTracker(Clock clock, LongSupplier monotonicNanos, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.clock = clock;
        this.monotonicNanos = monotonicNanos;
        this.capacity = capacity;
        Instant now = clock.instant();
        this.continuousSince = now;
        this.lastWall = now;
        this.lastNanos = monotonicNanos.getAsLong();
    }

    public ForegroundActivityTracker(Clock clock) {
        this(clock, System::nanoTime, DEFAULT_CAPACITY);
    }

    /** Records eligible activity; the caller has already established eligibility and a verified account. */
    public synchronized void record(UUID account) {
        Instant now = checkContinuity();
        Instant cutoff = now.minus(WINDOW);
        if (latest.containsKey(account)) {
            latest.put(account, now);
            return;
        }
        if (latest.size() >= capacity) {
            expire(cutoff);
            if (latest.size() >= capacity) {
                lastOmission = now;
                return;
            }
        }
        latest.put(account, now);
    }

    public synchronized ForegroundAccounts snapshot() {
        Instant now = checkContinuity();
        Instant cutoff = now.minus(WINDOW);
        expire(cutoff);
        int count = latest.size();
        boolean truncated = lastOmission != null && !lastOmission.isBefore(cutoff);
        if (!truncated) lastOmission = null;
        if (truncated) return new ForegroundAccounts(ForegroundAccounts.State.TRUNCATED, null, count);
        if (now.isBefore(continuousSince.plus(WINDOW))) {
            return new ForegroundAccounts(ForegroundAccounts.State.PARTIAL_STARTUP, null, count);
        }
        return new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, count, null);
    }

    private void expire(Instant cutoff) {
        for (Iterator<Instant> it = latest.values().iterator(); it.hasNext(); ) {
            if (it.next().isBefore(cutoff)) it.remove();
        }
    }

    /** Detects a wall-clock jump relative to the monotonic clock; clears identities and restarts the warm-up. */
    private Instant checkContinuity() {
        Instant now = clock.instant();
        long nanos = monotonicNanos.getAsLong();
        Duration wall = Duration.between(lastWall, now);
        Duration mono = Duration.ofNanos(nanos - lastNanos);
        if (wall.minus(mono).abs().compareTo(DISCONTINUITY_TOLERANCE) > 0) {
            latest.clear();
            lastOmission = null;
            continuousSince = now;
        }
        lastWall = now;
        lastNanos = nanos;
        return now;
    }
}
