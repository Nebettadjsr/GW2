package application.ops;

import application.ops.OpsTestSupport.TestClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestAggregationTest {
    private final TestClock clock = new TestClock(Instant.parse("2026-11-14T10:00:20Z"));
    private final ForegroundActivityTracker activity = new ForegroundActivityTracker(clock, clock, 10);
    private final List<MinuteBucket> minutes = new ArrayList<>();
    private final HistoryStore store = new HistoryStore() {
        public void appendMinute(MinuteBucket b) { minutes.add(b); }
        public Snapshot snapshot() { return new Snapshot(true, false, List.copyOf(minutes), List.of()); }
    };
    private final OperationalHistoryService service = new OperationalHistoryService(clock, activity, store);

    private void observe(String path, int status, long millis, String account) {
        service.observe("GET", path, java.util.Optional.ofNullable(account), status, false, millis * 1_000_000);
    }

    @Test
    void partialFirstMinuteThenIdleMinutesStoreNothing() {
        observe("/api/account/bank", 200, 10, UUID.randomUUID().toString());
        observe("/api/account/bank", 404, 3000, UUID.randomUUID().toString());
        observe("/api/account/bank", 500, 6000, null);
        service.observe("GET", "/api/account/bank", java.util.Optional.empty(), 200, true, 1_000_000);
        clock.advance(Duration.ofSeconds(50)); // 10:01:10
        service.flush();
        assertEquals(1, minutes.size());
        MinuteBucket first = minutes.get(0);
        assertEquals(Instant.parse("2026-11-14T10:00:00Z"), first.startUtc());
        assertEquals(40, first.observedSeconds());
        assertEquals(MinuteBucket.Completeness.PARTIAL, first.completeness());
        RouteGroupAggregate a = first.routeGroups().get(0);
        assertEquals(RouteGroup.ACCOUNT_DATA, a.group());
        assertEquals(4, a.requests());
        assertEquals(1, a.success());
        assertEquals(1, a.clientError());
        assertEquals(1, a.serverError());
        assertEquals(1, a.failed());
        assertEquals(6000_000, a.elapsedMicrosMax());
        assertEquals(10_000 + 3_000_000 + 6_000_000 + 1_000, a.elapsedMicrosSum());
        assertEquals(1, a.histogram().get(8).count());
        assertEquals(2, a.histogram().get(0).count());
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, first.foregroundAccounts().state());

        clock.advance(Duration.ofMinutes(2)); // idle minutes: nothing is stored for them
        service.flush();
        assertEquals(1, minutes.size(), "idle minutes create no zero bucket and no catch-up row");
    }

    @Test
    void delayedFlushNeverBackdatesTheCurrentActivitySnapshot() {
        observe("/api/account/bank", 200, 5, UUID.randomUUID().toString());
        clock.advance(Duration.ofMinutes(6)); // 10:06:20: flush delayed far beyond the 10:01 boundary
        service.flush();
        assertEquals(1, minutes.size());
        assertEquals(Instant.parse("2026-11-14T10:00:00Z"), minutes.get(0).startUtc());
        // Boundary-time reading (warm-up), never the 10:06:20 reading; no COMPLETE/zero claim.
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, minutes.get(0).foregroundAccounts().state());
        assertEquals(1, minutes.get(0).routeGroups().get(0).requests());
    }

    @Test
    void onTimeFlushKeepsTheActivityReading() {
        observe("/api/account/bank", 200, 5, UUID.randomUUID().toString());
        clock.advance(Duration.ofSeconds(45)); // 10:01:05
        service.flush();
        assertEquals(ForegroundAccounts.State.PARTIAL_STARTUP, minutes.get(0).foregroundAccounts().state());
    }

    @Test
    void subTenSecondFlushReportsTheBoundaryCountNotTheExpiredCurrentOne() {
        clock.advance(Duration.ofSeconds(40)); // 10:01:00
        observe("/api/account/bank", 200, 5, UUID.randomUUID().toString());
        for (int i = 0; i < 4; i++) { clock.advance(Duration.ofSeconds(60)); service.flush(); }
        observe("/api/items/5", 200, 5, null); // an active 10:05 minute (non-foreground) carries the reading // 10:05:00
        clock.advance(Duration.ofSeconds(65)); // 10:06:05: the 10:01:00 activity has left the window now
        service.flush();
        assertEquals(0, activity.snapshot().exactCount());
        var m = minutes.get(minutes.size() - 1);
        assertEquals(Instant.parse("2026-11-14T10:05:00Z"), m.startUtc());
        assertEquals(ForegroundAccounts.State.COMPLETE, m.foregroundAccounts().state());
        assertEquals(1, m.foregroundAccounts().exactCount()); // as of 10:06:00, not the 10:06:05 reading
    }

    @Test
    void activityAfterTheBoundaryMakesTheReadingUnavailable() {
        String account = UUID.randomUUID().toString();
        clock.advance(Duration.ofSeconds(40));
        observe("/api/account/bank", 200, 5, account);
        for (int i = 0; i < 4; i++) { clock.advance(Duration.ofSeconds(60)); service.flush(); }
        observe("/api/items/5", 200, 5, null); // an active 10:05 minute (non-foreground) carries the reading
        clock.advance(Duration.ofSeconds(65)); // 10:06:05
        activity.record(java.util.UUID.fromString(account)); // overwrites the timestamp after the boundary
        service.flush();
        assertEquals(ForegroundAccounts.State.UNAVAILABLE,
                minutes.get(minutes.size() - 1).foregroundAccounts().state());
    }

    @Test
    void longClockJumpCreatesOneBucketAndNoCatchUpMinutes() {
        observe("/api/account/bank", 200, 5, null);
        clock.advance(Duration.ofDays(3650)); // deterministic long suspend / forward jump
        observe("/api/account/bank", 200, 5, null); // request-side roll must stay O(1)
        clock.advance(Duration.ofMinutes(1));
        service.flush();
        assertEquals(2, minutes.size(), "exactly the two active minutes, no per-skipped-minute buckets");
        assertEquals(Instant.parse("2026-11-14T10:00:00Z"), minutes.get(0).startUtc());
    }

    @Test
    void excludedTrafficNeverTouchesAggregatesOrActivity() {
        String account = UUID.randomUUID().toString();
        for (String p : new String[] {"/api/system/status", "/api/system/status/details", "/api/sync/tasks/x",
                "/api/session/renew", "/api/session/logout", "/actuator/health", "/index.html"}) {
            observe(p, 200, 5, account);
        }
        clock.advance(Duration.ofMinutes(6));
        assertEquals(0, activity.snapshot().exactCount());
        service.flush();
        assertTrue(minutes.isEmpty(), "excluded traffic never creates an active minute");
    }

    @Test
    void nonForegroundAndUnverifiedRequestsDoNotCountAsPlayers() {
        clock.advance(Duration.ofMinutes(6));
        observe("/api/items/5", 200, 5, UUID.randomUUID().toString()); // shared data: not foreground
        observe("/api/account/bank", 401, 5, null);                    // unauthenticated
        observe("/api/account/bank", 200, 5, "not-a-guid");
        assertEquals(0, activity.snapshot().exactCount());
        observe("/api/account/bank", 403, 5, UUID.randomUUID().toString()); // app-level 4xx after resolution
        assertEquals(1, activity.snapshot().exactCount());
    }

    @Test
    void fullHourIsCompleteAndRollupSumsCoverage() {
        List<MinuteBucket> sixty = new ArrayList<>();
        Instant hour = Instant.parse("2026-11-14T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            sixty.add(new MinuteBucket(hour.plusSeconds(60L * i), 60, MinuteBucket.Completeness.COMPLETE,
                    new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, i, null), List.of()));
        }
        HourBucket h = HourBucket.rollUp(hour, sixty);
        assertEquals(HourBucket.Completeness.COMPLETE, h.completeness());
        assertEquals(3600, h.observedSeconds());
        assertEquals(59, h.activity().maxReportedActive());
        assertFalse(h.activity().anyTruncated());
    }

    @Test
    void persistedModelsHaveNoIdentityKeyHeaderBodyQueryOrDynamicPathField() throws Exception {
        Set<String> names = new HashSet<>();
        for (Class<?> c : new Class<?>[] {MinuteBucket.class, HourBucket.class, HourBucket.ActivitySummary.class,
                ForegroundAccounts.class, RouteGroupAggregate.class, LatencyHistogram.Bin.class}) {
            for (RecordComponent rc : c.getRecordComponents()) names.add(rc.getName().toLowerCase());
        }
        for (String forbidden : new String[] {"account", "accountid", "guid", "session", "key", "token", "header",
                "cookie", "body", "query", "path", "url", "name", "hash", "message", "exception", "ip"}) {
            assertFalse(names.contains(forbidden), forbidden);
        }
        String account = UUID.randomUUID().toString();
        observe("/api/account/bank", 200, 5, account);
        clock.advance(Duration.ofMinutes(1));
        service.flush();
        String json = JsonMapper.builder().build().writeValueAsString(service.history());
        assertFalse(json.contains(account));
        assertFalse(json.contains("/api/"));
    }

    @Test
    void telemetryFailureNeverPropagates() {
        var throwing = new HistoryStore() {
            public void appendMinute(MinuteBucket b) { throw new IllegalStateException("disk"); }
            public Snapshot snapshot() { throw new IllegalStateException("disk"); }
        };
        var s = new OperationalHistoryService(clock, activity, throwing);
        s.observe(null, null, null, 200, false, 1);
        clock.advance(Duration.ofMinutes(2));
        assertDoesNotThrow(s::flush);
    }

    @Test
    void missingMinutesStayGapsInTheRollup() {
        Instant hour = Instant.parse("2026-11-14T10:00:00Z");
        List<MinuteBucket> some = List.of(
                new MinuteBucket(hour, 60, MinuteBucket.Completeness.COMPLETE, ForegroundAccounts.unavailable(), List.of()),
                new MinuteBucket(hour.plusSeconds(1800), 60, MinuteBucket.Completeness.COMPLETE,
                        new ForegroundAccounts(ForegroundAccounts.State.TRUNCATED, null, 10), List.of()));
        HourBucket h = HourBucket.rollUp(hour, some);
        assertEquals(HourBucket.Completeness.PARTIAL, h.completeness());
        assertEquals(2, h.minuteBuckets());
        assertEquals(120, h.observedSeconds());
        assertTrue(h.activity().anyTruncated());
        assertTrue(h.activity().anyUnavailable());
        assertEquals(10, h.activity().maxReportedActive());
    }
}
