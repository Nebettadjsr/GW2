package perf;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Monotonic hard deadline of the prepared warm data (STORY-KPI-002). It starts at preparation (so setup,
 * preflight and baseline are included, not a clamped sum of stage durations) and is the effective
 * freshness ceiling: a stage is admitted only when its forecast window fits, and any stage that crosses
 * the deadline is rejected. The clock is injectable (nanoseconds, monotonic) so expiry is tested without sleeping.
 */
public final class WarmDeadline {
    private final long startNanos;
    private final long ceilingNanos;
    private final LongSupplier clock;

    public WarmDeadline(long startNanos, Duration ceiling, LongSupplier clock) {
        if (ceiling.isNegative() || ceiling.isZero()) throw new IllegalArgumentException("warm ceiling must be positive");
        this.startNanos = startNanos;
        this.ceilingNanos = ceiling.toNanos();
        this.clock = clock;
    }

    public static WarmDeadline start(Duration ceiling) { return new WarmDeadline(System.nanoTime(), ceiling, System::nanoTime); }

    public long elapsedMillis() { return Math.max(0, (clock.getAsLong() - startNanos) / 1_000_000); }

    public long remainingMillis() { return Math.max(0, (ceilingNanos - (clock.getAsLong() - startNanos)) / 1_000_000); }

    public boolean expired() { return clock.getAsLong() - startNanos >= ceilingNanos; }

    public long ceilingSeconds() { return ceilingNanos / 1_000_000_000L; }
}
