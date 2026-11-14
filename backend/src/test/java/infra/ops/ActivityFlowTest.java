package infra.ops;

import application.ops.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The service plus real sparse store under a fake clock: idle, long gaps, boundary closure and restart. */
class ActivityFlowTest {
    @TempDir Path dir;
    private Instant now = Instant.parse("2026-11-15T23:58:10Z"); // Sunday, last ISO-week minutes
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    };

    private OperationalHistoryService service() {
        var store = new SparseActivityStore(dir, clock);
        return new OperationalHistoryService(clock, new ForegroundActivityTracker(clock), store, store);
    }

    private long files() throws Exception { try (Stream<Path> s = Files.list(dir)) { return s.count(); } }

    @Test
    void idleProcessCrossesDayAndWeekWithNothingWritten() throws Exception {
        var svc = service();
        for (int i = 0; i < 3 * 24 * 60; i += 7) { now = now.plus(Duration.ofMinutes(7)); svc.flush(); }
        assertEquals(0, files());
        assertTrue(svc.listPeriods().isEmpty());
    }

    @Test
    void finalMinuteOfWeekClosesWithoutLaterTrafficAndLongGapAddsOnlyItsOwnMinute() throws Exception {
        var svc = service();
        now = Instant.parse("2026-11-15T23:59:20Z");
        svc.observe("GET", "/api/crafting/x", Optional.empty(), 200, false, 5_000_000);
        assertTrue(svc.listPeriods().isEmpty(), "the open minute is not persisted yet");
        now = Instant.parse("2026-11-16T00:00:30Z"); // past the UTC day and ISO-week boundary, no request
        var periods = svc.listPeriods();
        assertEquals(2, periods.size());
        assertTrue(periods.stream().allMatch(p -> p.state() == PeriodDescriptor.State.CLOSED));
        var week = periods.stream().filter(p -> p.kind() == PeriodDescriptor.Kind.WEEK).findFirst().orElseThrow();
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, week.coverage(), "startup minute is partial, not upgraded");
        now = Instant.parse("2027-02-20T08:00:10Z"); // months later
        svc.observe("GET", "/api/crafting/x", Optional.empty(), 200, false, 5_000_000);
        now = now.plusSeconds(60);
        assertEquals(4, svc.listPeriods().size(), "old activity kept; new day and week; nothing in between");
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(2, s.filter(p -> p.toString().endsWith(".csv")).count());
        }
        var week2 = svc.listPeriods().get(0);
        try (var in = svc.openDownload(week2.selectionId()).stream()) {
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(2, csv.split("\n").length, "header and the single active minute");
        }
    }

    @Test
    void restartAfterCleanFlushDoesNotDuplicateOrBackfill() throws Exception {
        var svc = service();
        now = Instant.parse("2026-11-15T10:00:10Z");
        svc.observe("GET", "/api/crafting/x", Optional.empty(), 200, false, 1_000);
        now = now.plusSeconds(60);
        svc.flush();
        long size = Files.size(dir.resolve("activity-2026-11-15.csv"));
        now = now.plus(Duration.ofHours(5));
        var again = service();
        again.flush();
        assertEquals(size, Files.size(dir.resolve("activity-2026-11-15.csv")));
        assertEquals(2, again.listPeriods().size());
    }

    @Test
    void weekStreamUsesFixedBufferOverManyRows() throws Exception {
        var store = new SparseActivityStore(dir, clock);
        for (int day = 9; day <= 15; day++) {
            for (int m = 0; m < 300; m++) {
                store.appendMinute(new MinuteBucket(Instant.parse("2026-11-%02dT00:00:00Z".formatted(day)).plusSeconds(60L * m),
                        60, MinuteBucket.Completeness.COMPLETE,
                        new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, 1, null),
                        java.util.List.of(new RouteGroupAggregate(RouteGroup.CRAFTING, 1, 1, 0, 0, 0, 5, 5,
                                LatencyHistogram.toBins(new long[LatencyHistogram.BINS])))));
            }
        }
        now = Instant.parse("2026-11-20T00:00:00Z");
        var week = store.listPeriods().stream().filter(p -> p.kind() == PeriodDescriptor.Kind.WEEK).findFirst().orElseThrow();
        var dl = store.openDownload(week.selectionId());
        long total = 0;
        int maxChunk = 0, headers = 0;
        Instant previous = Instant.MIN;
        var text = new StringBuilder();
        try (var in = dl.stream()) {
            byte[] buf = new byte[100_000];
            int n;
            while ((n = in.read(buf)) > 0) { total += n; maxChunk = Math.max(maxChunk, n); text.append(new String(buf, 0, n, StandardCharsets.US_ASCII)); }
        }
        assertTrue(maxChunk <= SparseActivityStore.STREAM_BUFFER_BYTES, "chunk " + maxChunk);
        assertEquals(week.retainedBytes(), total);
        for (String l : text.toString().split("\n")) {
            if (l.startsWith("schema_version")) { headers++; continue; }
            Instant t = Instant.parse(l.split(",")[1]);
            assertTrue(t.isAfter(previous));
            previous = t;
        }
        assertEquals(1, headers);
    }
}
