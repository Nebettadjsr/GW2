package application.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.LongSupplier;

/** Deterministic wall clock plus a monotonic source that advances with it unless a jump is simulated. */
final class OpsTestSupport {
    private OpsTestSupport() {}

    static final class TestClock extends Clock implements LongSupplier {
        private Instant now;
        private long nanos;

        TestClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); nanos += d.toNanos(); }

        /** Wall clock moves without the monotonic clock (a discontinuity). */
        void jumpWall(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long getAsLong() { return nanos; }
    }
}
