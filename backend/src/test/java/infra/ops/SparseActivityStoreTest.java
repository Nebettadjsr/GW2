package infra.ops;

import application.ops.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SparseActivityStoreTest {
    @TempDir Path dir;
    private Instant now = Instant.parse("2026-11-14T12:00:00Z");
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    };

    private SparseActivityStore store() { return new SparseActivityStore(dir, clock); }

    @Test
    void startupStopsWhenAnOverLimitFileCannotBeDeleted() throws Exception {
        var initial = store();
        initial.appendMinute(minute("2026-11-10T10:00:00Z", RouteGroup.CRAFTING));
        initial.appendMinute(minute("2026-11-11T10:00:00Z", RouteGroup.CRAFTING));
        Path oldest = dir.resolve("activity-2026-11-10.csv");
        boolean posix = Files.getFileStore(dir).supportsFileAttributeView("posix");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = posix
                ? Files.getPosixFilePermissions(dir) : null;
        try {
            if (posix) {
                Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir), "permissions are not enforced for this user");
            } else {
                org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
                Files.setAttribute(oldest, "dos:readonly", true);
            }
            var recovered = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> new SparseActivityStore(dir, clock, 1, 1 << 20, 32L << 20, 1024));
            assertFalse(recovered.periods().available(), "failed eviction is unavailable, not an endless startup");
            assertTrue(Files.exists(oldest), "failed unlink remains accounted for");
        } finally {
            if (posix) Files.setPosixFilePermissions(dir, permissions);
            else Files.setAttribute(oldest, "dos:readonly", false);
        }
    }

    @Test
    void pendingFailuresCannotEvictLossFlagsFromRetainedPartialDays() throws Exception {
        var s = store();
        var full = minute("2026-11-10T10:00:00Z", RouteGroup.CRAFTING);
        s.appendMinute(new MinuteBucket(full.startUtc(), 37, MinuteBucket.Completeness.PARTIAL,
                full.foregroundAccounts(), full.routeGroups()));
        for (int i = 1; i <= SparseActivityStore.MAX_LOSS_DAYS + 2; i++) {
            var date = java.time.LocalDate.parse("2026-11-10").plusDays(i);
            Path obstructed = dir.resolve("activity-" + date + ".csv");
            Files.createDirectory(obstructed);
            Files.writeString(obstructed.resolve("blocker"), "controlled first-append failure");
            now = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            s.appendMinute(minute(date + "T10:00:00Z", RouteGroup.CRAFTING));
        }
        var retained = find(s, PeriodDescriptor.Kind.DAY, "2026-11-10T00:00:00Z");
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, retained.coverage());
    }

    private static MinuteBucket minute(String utc, RouteGroup... groups) { return minute(utc, 1, groups); }

    private static MinuteBucket minute(String utc, long width, RouteGroup... groups) {
        long big = width > 1 ? Long.MAX_VALUE : 100;
        List<RouteGroupAggregate> aggs = new ArrayList<>();
        for (RouteGroup g : groups) {
            long[] bins = new long[LatencyHistogram.BINS];
            java.util.Arrays.fill(bins, big);
            aggs.add(new RouteGroupAggregate(g, big, big, big, big, big, big, big, LatencyHistogram.toBins(bins)));
        }
        return new MinuteBucket(Instant.parse(utc), 60, MinuteBucket.Completeness.COMPLETE,
                new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, 3, null), aggs);
    }

    private static String read(OperationalPeriodStore.Download d) throws IOException {
        try (var in = d.stream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private long ownedBytes() throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.mapToLong(p -> p.toFile().length()).sum(); }
    }

    private PeriodDescriptor find(SparseActivityStore s, PeriodDescriptor.Kind kind, String fromUtc) {
        return s.listPeriods().stream().filter(p -> p.kind() == kind && p.fromUtc().equals(Instant.parse(fromUtc)))
                .findFirst().orElseThrow();
    }

    @Test
    void idleStoreCreatesNothingAndListsNothing() throws Exception {
        var s = store();
        for (String t : new String[] {"2026-11-14T12:01:00Z", "2026-11-15T00:00:00Z", "2026-11-16T00:00:00Z"}) {
            now = Instant.parse(t);
            assertTrue(s.listPeriods().isEmpty());
            assertTrue(s.snapshot().available());
        }
        s.appendMinute(minute("2026-11-14T12:00:00Z")); // no route group: not an active minute
        try (Stream<Path> st = Files.list(dir)) { assertEquals(0, st.count(), "no file or index is created while idle"); }
    }

    @Test
    void sparseRowsKeepCorrelatedFieldsAndNoIdentity() throws Exception {
        var s = store();
        s.appendMinute(minute("2026-11-14T10:05:00Z", RouteGroup.ACCOUNT_DATA, RouteGroup.CRAFTING));
        now = Instant.parse("2026-11-14T12:00:00Z");
        var day = find(s, PeriodDescriptor.Kind.DAY, "2026-11-14T00:00:00Z");
        assertEquals(PeriodDescriptor.State.OPEN, day.state());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, day.coverage());
        assertEquals("gw2-activity-2026-11-14.csv", day.filename());
        String csv = read(s.openDownload(day.selectionId()));
        String[] lines = csv.split("\n");
        assertEquals(SparseActivityStore.HEADER, lines[0]);
        assertEquals(3, lines.length);
        assertTrue(lines[1].startsWith("1,2026-11-14T10:05:00Z,COMPLETE,3,COMPLETE,ACCOUNT_DATA,100,"));
        assertEquals(23, lines[1].split(",").length);
        assertEquals(day.retainedBytes(), csv.length());
        String all = csv + day + s.listPeriods();
        for (String forbidden : new String[] {"/api", "guid", "token", "cookie", "Bearer", "127.0.0.1", "?"}) {
            assertFalse(all.contains(forbidden), forbidden);
        }
        assertTrue(Files.readAllLines(dir.resolve("activity-2026-11-14.csv")).stream().skip(1)
                .allMatch(l -> l.matches("[0-9TZ:,_A-Z-]+")));
    }

    @Test
    void closureAdvancesWithClockAndIdleMonthKeepsOldDayWithoutRewrite() throws Exception {
        var s = store();
        s.appendMinute(minute("2026-11-14T23:59:00Z", RouteGroup.CRAFTING));
        var file = dir.resolve("activity-2026-11-14.csv");
        long mtime = Files.getLastModifiedTime(file).toMillis();
        assertEquals(PeriodDescriptor.State.OPEN, find(s, PeriodDescriptor.Kind.DAY, "2026-11-14T00:00:00Z").state());
        now = Instant.parse("2026-11-15T00:00:00Z"); // boundary only, no later request
        var closed = find(s, PeriodDescriptor.Kind.DAY, "2026-11-14T00:00:00Z");
        assertEquals(PeriodDescriptor.State.CLOSED, closed.state());
        assertEquals(PeriodDescriptor.Coverage.FULL, closed.coverage());
        assertEquals(PeriodDescriptor.State.OPEN, find(s, PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z").state());
        now = Instant.parse("2026-12-20T00:00:00Z"); // an idle month later
        assertEquals(PeriodDescriptor.State.CLOSED, find(s, PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z").state());
        assertEquals(2, s.listPeriods().size());
        assertEquals(mtime, Files.getLastModifiedTime(file).toMillis());
        assertEquals(2, Files.list(dir).count(), "one day file plus its index; idle time creates nothing");
    }

    @Test
    void exactUtcDayAndIsoWeekBoundariesAndSingleHeaderWeekStream() throws Exception {
        var s = store();
        // Sunday 2026-11-15 23:59 is in ISO week starting 2026-11-09; Monday 2026-11-16 00:00 starts the next.
        s.appendMinute(minute("2026-11-08T23:59:00Z", RouteGroup.CRAFTING));
        s.appendMinute(minute("2026-11-09T00:00:00Z", RouteGroup.CRAFTING));
        s.appendMinute(minute("2026-11-15T23:59:00Z", RouteGroup.CRAFTING));
        s.appendMinute(minute("2026-11-16T00:00:00Z", RouteGroup.CRAFTING));
        now = Instant.parse("2026-11-20T00:00:00Z");
        var w = find(s, PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z");
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), w.toUtc());
        assertEquals("gw2-activity-week-2026-W46.csv", w.filename());
        String csv = read(s.openDownload(w.selectionId()));
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length, "one header plus exactly the two in-week rows");
        assertTrue(lines[1].contains("2026-11-09T00:00:00Z"));
        assertTrue(lines[2].contains("2026-11-15T23:59:00Z"));
        assertEquals(w.retainedBytes(), csv.length());
        assertEquals(0, Files.list(dir).filter(p -> p.getFileName().toString().contains("week")).count());
        var d = find(s, PeriodDescriptor.Kind.DAY, "2026-11-08T00:00:00Z");
        assertEquals(2, read(s.openDownload(d.selectionId())).split("\n").length);
        assertEquals(PeriodDescriptor.Coverage.FULL, w.coverage());
    }

    @Test
    void rowsAreNeverSplitAndCapMarksDayAndWeekTruncated() throws Exception {
        var s = new SparseActivityStore(dir, clock, 30, 2_000, 32L << 20, 1024);
        for (int i = 0; i < 20; i++) s.appendMinute(minute("2026-11-10T10:%02d:00Z".formatted(i), 2, RouteGroup.CRAFTING));
        now = Instant.parse("2026-11-20T00:00:00Z");
        var day = find(s, PeriodDescriptor.Kind.DAY, "2026-11-10T00:00:00Z");
        assertTrue(day.truncated());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, day.coverage());
        assertTrue(Files.size(dir.resolve("activity-2026-11-10.csv")) <= 2_000);
        var week = find(s, PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z");
        assertTrue(week.truncated());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, week.coverage());
        String csv = read(s.openDownload(day.selectionId()));
        assertTrue(csv.endsWith("\n"));
        for (String l : csv.split("\n")) assertEquals(23, l.split(",").length);
        assertTrue(csv.split("\n").length > 1, "durable whole rows stay downloadable");
        var again = new SparseActivityStore(dir, clock, 30, 2_000, 32L << 20, 1024);
        assertTrue(find(again, PeriodDescriptor.Kind.DAY, "2026-11-10T00:00:00Z").truncated());
    }

    @Test
    void maximumWidthRowFitsDefaultSchemaBounds() {
        var row = minute("2026-11-14T10:05:00Z", 2, RouteGroup.OTHER_API);
        var s = store();
        s.appendMinute(row);
        long bytes = dir.resolve("activity-2026-11-14.csv").toFile().length() - SparseActivityStore.HEADER.length() - 1;
        assertTrue(bytes < SparseActivityStore.MAX_ROW_BYTES / 2, "widest row is " + bytes + " bytes");
        // 2 MiB / widest row is far above one minute of eight groups, and the arithmetic holds per row.
        assertTrue(8L * 1440 * bytes > 0);
    }

    @Test
    void thirtyDayLimitEvictsWholeOldestDayAndMarksWeekPruned() throws Exception {
        var s = new SparseActivityStore(dir, clock, 3, 1 << 20, 32L << 20, 1024);
        for (String d : new String[] {"2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12"}) {
            s.appendMinute(minute(d + "T10:00:00Z", RouteGroup.CRAFTING));
        }
        now = Instant.parse("2026-11-20T00:00:00Z");
        assertFalse(Files.exists(dir.resolve("activity-2026-11-09.csv")));
        assertEquals(3, s.listPeriods().stream().filter(p -> p.kind() == PeriodDescriptor.Kind.DAY).count());
        var week = find(s, PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z");
        assertTrue(week.pruned());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, week.coverage());
        assertEquals(4, read(s.openDownload(week.selectionId())).split("\n").length, "retained rows stay downloadable");
        assertTrue(s.snapshot().retentionTruncated());
        assertTrue(find(new SparseActivityStore(dir, clock, 3, 1 << 20, 32L << 20, 1024),
                PeriodDescriptor.Kind.WEEK, "2026-11-09T00:00:00Z").pruned());
    }

    @Test
    void ownedBytesIncludingIndexAndScratchStayUnderCeiling() throws Exception {
        long ceiling = 40_000; // 2 * index reserve (2 x 4 KiB) leaves ~32 KB for day files
        var s = new SparseActivityStore(dir, clock, 30, 20_000, ceiling, 4096);
        Files.writeString(dir.resolve("activity-index.tmp"), "x".repeat(100)); // abandoned scratch
        s = new SparseActivityStore(dir, clock, 30, 20_000, ceiling, 4096);
        assertFalse(Files.exists(dir.resolve("activity-index.tmp")));
        for (int day = 1; day <= 25; day++) {
            for (int m = 0; m < 5; m++) {
                s.appendMinute(minute("2026-10-%02dT10:%02d:00Z".formatted(day, m), 2, RouteGroup.CRAFTING));
                assertTrue(ownedBytes() <= ceiling, "owned bytes " + ownedBytes());
            }
        }
        long files = 0;
        try (Stream<Path> st = Files.list(dir)) {
            for (Path p : st.toList()) if (p.getFileName().toString().endsWith(".csv")) files += Files.size(p);
        }
        assertTrue(files + 2 * 4096 <= ceiling, "day files plus reserved index and scratch within ceiling: " + files);
        assertTrue(Files.list(dir).count() < 25);
    }

    @Test
    void restartKeepsWholeRowsRepairsTornTailAndNeverDuplicates() throws Exception {
        var s = store();
        s.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING));
        s.appendMinute(minute("2026-11-14T10:01:00Z", RouteGroup.CRAFTING));
        Path file = dir.resolve("activity-2026-11-14.csv");
        Files.writeString(file, "1,2026-11-14T10:02:00Z,COMPLE", StandardOpenOption.APPEND);
        var again = store();
        assertEquals(3, Files.readAllLines(file).size());
        again.appendMinute(minute("2026-11-14T10:01:00Z", RouteGroup.CRAFTING)); // duplicate closed row
        again.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING)); // backdated
        again.appendMinute(minute("2026-11-14T10:02:00Z", RouteGroup.CRAFTING));
        assertEquals(4, Files.readAllLines(file).size());
        now = Instant.parse("2026-11-16T00:00:00Z");
        var d = find(again, PeriodDescriptor.Kind.DAY, "2026-11-14T00:00:00Z");
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, d.coverage(), "torn row is a known persistence loss");
        assertTrue(Files.readString(dir.resolve("activity-index")).contains("L,2026-11-14"));
        assertEquals(1, Files.list(dir).filter(p -> p.toString().endsWith(".csv")).count());
    }

    @Test
    void firstStartDeletesLegacyAndScratchButNotUnrelatedFiles() throws Exception {
        for (String n : new String[] {"minute-history.jsonl", "hour-history.jsonl", "minute-history.jsonl.tmp",
                "hour-history.jsonl123.tmp", "activity-index.tmp"}) Files.writeString(dir.resolve(n), "{}");
        Files.writeString(dir.resolve("notes.txt"), "keep");
        Files.writeString(dir.resolve("minute-history.jsonl.bak"), "keep");
        var s = store();
        try (Stream<Path> st = Files.list(dir)) {
            assertEquals(List.of("minute-history.jsonl.bak", "notes.txt"),
                    st.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertTrue(s.listPeriods().isEmpty());
        assertTrue(s.snapshot().minutes().isEmpty());
    }

    @Test
    void unavailableStorageAndUnknownSelectionsAreSafe() throws Exception {
        Path blocker = Files.writeString(dir.resolve("file"), "x");
        var broken = new SparseActivityStore(blocker, clock);
        assertFalse(broken.snapshot().available());
        assertEquals(OperationalPeriodStore.Status.UNAVAILABLE, broken.openDownload("x").status());
        assertDoesNotThrow(() -> broken.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING)));
        var s = store();
        s.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING));
        for (String id : new String[] {null, "", "../../etc/passwd", "ZDoyMDI2LTExLTAx", "w:2026-11-09"}) {
            assertEquals(OperationalPeriodStore.Status.NOT_FOUND, s.openDownload(id).status(), String.valueOf(id));
        }
        String id = s.listPeriods().get(1).selectionId();
        assertFalse(id.contains("/") || id.contains("."));
    }

    @Test
    void snapshotCompatibilityRebuildsMinutesAndNeverHours() {
        var s = store();
        s.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING, RouteGroup.ACCOUNT_DATA));
        var snap = s.snapshot();
        assertEquals(1, snap.minutes().size());
        assertEquals(2, snap.minutes().get(0).routeGroups().size());
        assertEquals(3, snap.minutes().get(0).foregroundAccounts().exactCount());
        assertTrue(snap.hours().isEmpty());
    }

    private MinuteBucket partial(String utc, int observed) {
        var b = minute(utc, RouteGroup.CRAFTING);
        return new MinuteBucket(b.startUtc(), observed, MinuteBucket.Completeness.PARTIAL, b.foregroundAccounts(),
                b.routeGroups());
    }

    private PeriodDescriptor closedDay(SparseActivityStore s) {
        now = Instant.parse("2026-11-20T00:00:00Z");
        return find(s, PeriodDescriptor.Kind.DAY, "2026-11-14T00:00:00Z");
    }

    @Test
    void observedSecondsSurviveCsvRoundTripAndShortIntervalDiffersFromNone() throws Exception {
        var s = store();
        s.appendMinute(partial("2026-11-14T10:00:00Z", 37));
        s.appendMinute(partial("2026-11-14T10:01:00Z", 0));
        var again = store();
        var mins = again.snapshot().minutes();
        assertEquals(37, mins.get(0).observedSeconds());
        assertEquals(0, mins.get(1).observedSeconds());
        assertTrue(Files.readString(dir.resolve("activity-2026-11-14.csv")).contains(",37\n"));
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(again).coverage());
    }

    @Test
    void partialRowsStayPartialAfterIndexLossAndLostIndexNeverUpgradesToFull() throws Exception {
        var s = store();
        s.appendMinute(partial("2026-11-14T10:00:00Z", 37));
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(s).coverage());
        Files.delete(dir.resolve("activity-index"));
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(store()).coverage());
        // a healthy complete day also becomes conservative once its index is gone
        var clean = new SparseActivityStore(dir.resolve("c"), clock);
        clean.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING));
        assertEquals(PeriodDescriptor.Coverage.FULL, closedDay(clean).coverage());
        assertEquals(PeriodDescriptor.Coverage.FULL, closedDay(new SparseActivityStore(dir.resolve("c"), clock)).coverage());
        Files.delete(dir.resolve("c").resolve("activity-index"));
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(new SparseActivityStore(dir.resolve("c"), clock)).coverage());
        Files.writeString(dir.resolve("c").resolve("activity-index"), "garbage\n");
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(new SparseActivityStore(dir.resolve("c"), clock)).coverage());
    }

    @Test
    void malformedRowIsKnownLossAfterRestart() throws Exception {
        var s = store();
        s.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING));
        Files.writeString(dir.resolve("activity-2026-11-14.csv"), "bad,row\n", StandardOpenOption.APPEND);
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(store()).coverage());
    }

    @Test
    void failedFirstAppendLossSurvivesLaterSuccess() throws Exception {
        Path root = Files.createDirectory(dir.resolve("r"));
        var s = new SparseActivityStore(root.resolve("sub"), clock);
        Files.writeString(root.resolve("sub"), "blocker"); // the directory cannot be created: first append fails
        s.appendMinute(minute("2026-11-14T10:00:00Z", RouteGroup.CRAFTING));
        Files.delete(root.resolve("sub"));
        s.appendMinute(minute("2026-11-14T10:05:00Z", RouteGroup.CRAFTING));
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(s).coverage());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, closedDay(new SparseActivityStore(root.resolve("sub"), clock)).coverage());
    }

    @Test
    void failedEvictionKeepsAccountingStopsAdmissionAndReportsUnavailable() throws Exception {
        var s = new SparseActivityStore(dir, clock, 2, 20_000, 32L << 20, 4096);
        s.appendMinute(minute("2026-11-10T10:00:00Z", RouteGroup.CRAFTING));
        s.appendMinute(minute("2026-11-11T10:00:00Z", RouteGroup.CRAFTING));
        Path oldest = dir.resolve("activity-2026-11-10.csv");
        // A non-empty directory under the file name makes the unlink fail for real.
        Files.delete(oldest);
        Files.createDirectory(oldest);
        Files.writeString(oldest.resolve("x"), "x");
        s.appendMinute(minute("2026-11-12T10:00:00Z", RouteGroup.CRAFTING));
        assertFalse(Files.exists(dir.resolve("activity-2026-11-12.csv")), "no admission while the old day cannot go");
        assertFalse(s.periods().available());
        assertFalse(s.snapshot().available());
        assertTrue(Files.exists(dir.resolve("activity-2026-11-11.csv")));
    }

    @Test
    void undeletableLegacyArtifactStaysCountedInTheBound() throws Exception {
        Path legacy = dir.resolve("minute-history.jsonl");
        Files.createDirectory(legacy); // not a regular file: untouched and unrecognized for deletion
        Files.writeString(dir.resolve("hour-history.jsonl"), "x".repeat(30_000));
        var s = new SparseActivityStore(dir, clock, 30, 20_000, 40_000, 4096);
        long before = ownedBytes();
        for (int m = 0; m < 20; m++) s.appendMinute(minute("2026-11-14T10:%02d:00Z".formatted(m), 2, RouteGroup.CRAFTING));
        assertTrue(ownedBytes() <= 40_000, "owned bytes " + ownedBytes() + " from " + before);
    }

    @Test
    void oversizedLoadedDayFileIsTrimmedToTheDayCap() throws Exception {
        var big = new SparseActivityStore(dir, clock, 30, 1 << 20, 32L << 20, 1024);
        for (int m = 0; m < 20; m++) big.appendMinute(minute("2026-11-14T10:%02d:00Z".formatted(m), 2, RouteGroup.CRAFTING));
        long full = Files.size(dir.resolve("activity-2026-11-14.csv"));
        var capped = new SparseActivityStore(dir, clock, 30, full / 2, 32L << 20, 1024);
        long size = Files.size(dir.resolve("activity-2026-11-14.csv"));
        assertTrue(size <= full / 2);
        assertTrue(Files.readString(dir.resolve("activity-2026-11-14.csv")).endsWith("\n"));
        var d = closedDay(capped);
        assertTrue(d.truncated());
        assertEquals(PeriodDescriptor.Coverage.PARTIAL, d.coverage());
    }

    @Test
    void unrelatedActivityPrefixedTempFileIsNotDeleted() throws Exception {
        Files.writeString(dir.resolve("activity-report.tmp"), "keep");
        Files.writeString(dir.resolve("activity-index.tmp"), "x");
        store();
        assertTrue(Files.exists(dir.resolve("activity-report.tmp")));
        assertFalse(Files.exists(dir.resolve("activity-index.tmp")));
    }

    @Test
    void unreadableStoreIsUnavailableNotEmpty() throws Exception {
        Path blocker = Files.writeString(dir.resolve("file"), "x");
        assertFalse(new SparseActivityStore(blocker, clock).periods().available());
        assertTrue(store().periods().available());
    }
}
