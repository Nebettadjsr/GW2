package application.ops;

import application.ops.OpsTestSupport.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForegroundActivityTrackerTest {
    private final TestClock clock = new TestClock(Instant.parse("2026-11-14T10:00:00Z"));

    private ForegroundActivityTracker tracker(int capacity) {
        return new ForegroundActivityTracker(clock, clock, capacity);
    }

    private static UUID id(int n) { return new UUID(0, n); }

    private void warm() { clock.advance(Duration.ofMinutes(6)); }

    @Test
    void sameAccountCountsOnceAndRefreshesItsExpiry() {
        var t = tracker(10);
        t.record(id(1));
        clock.advance(Duration.ofMinutes(4));
        t.record(id(1));
        clock.advance(Duration.ofMinutes(4));
        var s = t.snapshot();
        assertEquals(ForegroundAccounts.State.COMPLETE, s.state());
        assertEquals(1, s.exactCount());
        assertNull(s.lowerBound());
    }

    @Test
    void twoAccountsCountTwice() {
        var t = tracker(10);
        warm();
        t.record(id(1));
        t.record(id(2));
        assertEquals(2, t.snapshot().exactCount());
    }

    @Test
    void exactlyFiveMinutesOldIsInclusiveAndOlderIsExpired() {
        var t = tracker(10);
        warm();
        t.record(id(1));
        clock.advance(Duration.ofMinutes(5));
        assertEquals(1, t.snapshot().exactCount());
        clock.advance(Duration.ofNanos(1));
        assertEquals(0, t.snapshot().exactCount());
    }

    @Test
    void trailingWindowIsNotAUnionOfMinuteBuckets() {
        var t = tracker(10);
        warm();
        clock.advance(Duration.ofSeconds(30)); // :30 of a minute
        t.record(id(1));
        clock.advance(Duration.ofMinutes(5).plusSeconds(10));
        t.record(id(2));
        assertEquals(1, t.snapshot().exactCount());
    }

    @Test
    void startupIsPartialUntilFiveContinuousMinutes() {
        var t = tracker(10);
        t.record(id(1));
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        var s = t.snapshot();
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, s.state());
        assertNull(s.exactCount());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(ForegroundAccounts.State.COMPLETE, t.snapshot().state());
    }

    @Test
    void clockDiscontinuityClearsIdentitiesAndRestartsWarmup() {
        var t = tracker(10);
        warm();
        t.record(id(1));
        clock.jumpWall(Duration.ofHours(1));
        var s = t.snapshot();
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, s.state());
        assertEquals(0, s.lowerBound());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, t.snapshot().exactCount());
        clock.jumpWall(Duration.ofSeconds(-30));
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, t.snapshot().state());
    }

    @Test
    void capacityKeepsUpdatingExistingRejectsNewAndReportsLowerBound() {
        var t = tracker(2);
        warm();
        t.record(id(1));
        t.record(id(2));
        t.record(id(3)); // refused: nobody expired
        clock.advance(Duration.ofMinutes(1));
        t.record(id(1)); // existing still updates
        var s = t.snapshot();
        assertEquals(ForegroundAccounts.State.TRUNCATED, s.state());
        assertNull(s.exactCount());
        assertEquals(2, s.lowerBound());
    }

    @Test
    void truncationLowerBoundFollowsRetainedCountAndClearsWhenOmissionAgesOut() {
        var t = tracker(2);
        warm();
        t.record(id(1));
        clock.advance(Duration.ofMinutes(3));
        t.record(id(2));
        t.record(id(3)); // omitted at +3
        clock.advance(Duration.ofMinutes(2).plusSeconds(1)); // id1 expired; omission still inside window
        var s = t.snapshot();
        assertEquals(ForegroundAccounts.State.TRUNCATED, s.state());
        assertEquals(1, s.lowerBound()); // current retained count, not the cap
        t.record(id(4)); // capacity now exists
        clock.advance(Duration.ofMinutes(3));
        assertEquals(ForegroundAccounts.State.COMPLETE, t.snapshot().state());
        assertEquals(1, t.snapshot().exactCount());
    }

    @Test
    void expiredEntriesFreeCapacityBeforeANewAdmission() {
        var t = tracker(1);
        warm();
        t.record(id(1));
        clock.advance(Duration.ofMinutes(6));
        t.record(id(2));
        assertEquals(1, t.snapshot().exactCount());
        assertEquals(ForegroundAccounts.State.COMPLETE, t.snapshot().state());
    }
}
