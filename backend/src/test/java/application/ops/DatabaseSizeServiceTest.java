package application.ops;

import application.ops.OpsTestSupport.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSizeServiceTest {
    private final TestClock clock = new TestClock(Instant.parse("2026-11-14T10:00:00Z"));

    @Test
    void cachesForFiveMinutesThenRefreshes() {
        AtomicInteger calls = new AtomicInteger();
        var s = new DatabaseSizeService(() -> calls.incrementAndGet() * 100L, clock);
        assertEquals(100, s.current().bytes());
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertEquals(100, s.current().bytes());
        assertEquals(1, calls.get());
        clock.advance(Duration.ofNanos(1));
        assertEquals(200, s.current().bytes());
    }

    @Test
    void failedRefreshWithPriorSuccessIsStaleWithOriginalTimestamp() {
        boolean[] fail = {false};
        var s = new DatabaseSizeService(() -> { if (fail[0]) throw new java.sql.SQLException("down"); return 7; }, clock);
        Instant first = s.current().measuredAt();
        fail[0] = true;
        clock.advance(Duration.ofMinutes(6));
        var stale = s.current();
        assertEquals(DatabaseSize.State.STALE, stale.state());
        assertEquals(7, stale.bytes());
        assertEquals(first, stale.measuredAt());
    }

    @Test
    void firstFailureIsUnavailableNotZeroAndTrueZeroIsAvailable() {
        var s = new DatabaseSizeService(() -> { throw new java.sql.SQLException("down"); }, clock);
        var r = s.current();
        assertEquals(DatabaseSize.State.UNAVAILABLE, r.state());
        assertNull(r.bytes());
        var zero = new DatabaseSizeService(() -> 0, clock).current();
        assertEquals(DatabaseSize.State.AVAILABLE, zero.state());
        assertEquals(0L, zero.bytes());
    }

    @Test
    void concurrentRefreshesCoalesceIntoOneQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var s = new DatabaseSizeService(() -> { calls.incrementAndGet(); release.await(); return 42; }, clock);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<Future<DatabaseSize>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(s::current));
            Thread.sleep(300);
            release.countDown();
            for (var f : futures) assertEquals(42, f.get(5, TimeUnit.SECONDS).bytes());
            assertEquals(1, calls.get());
        } finally { pool.shutdownNow(); }
    }
}
