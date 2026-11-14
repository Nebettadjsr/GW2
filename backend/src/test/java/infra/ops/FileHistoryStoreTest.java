package infra.ops;

import application.ops.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileHistoryStoreTest {
    @TempDir Path dir;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");

    private static RouteGroupAggregate agg(RouteGroup g) {
        return new RouteGroupAggregate(g, 5, 4, 1, 0, 0, 1000, 400, LatencyHistogram.toBins(new long[] {5, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

    private static MinuteBucket minute(int i, int groups) {
        List<RouteGroupAggregate> list = new ArrayList<>();
        for (int g = 0; g < groups; g++) list.add(agg(RouteGroup.values()[g]));
        return new MinuteBucket(T0.plusSeconds(60L * i), 60, MinuteBucket.Completeness.COMPLETE,
                new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, 2, null), list);
    }

    @Test
    void retentionCountsTopLevelBucketsNotRouteGroupRows() {
        var store = new FileHistoryStore(dir, mapper);
        for (int i = 0; i < 365; i++) store.appendMinute(minute(i, 8));
        var snap = store.snapshot();
        assertEquals(360, snap.minutes().size());
        assertEquals(T0.plusSeconds(60L * 5), snap.minutes().get(0).startUtc());
        assertEquals(8, snap.minutes().get(0).routeGroups().size());
        assertTrue(snap.retentionTruncated());
        assertTrue(snap.available());
        for (int i = 0; i < 725; i++) {
            store.appendHour(new HourBucket(T0.plusSeconds(3600L * i), 3600, HourBucket.Completeness.COMPLETE, 60,
                    new HourBucket.ActivitySummary(1, false, false, false), List.of(agg(RouteGroup.CRAFTING))));
        }
        assertEquals(720, store.snapshot().hours().size());
        // survives a restart with the same bounds
        var again = new FileHistoryStore(dir, mapper);
        assertEquals(360, again.snapshot().minutes().size());
        assertEquals(720, again.snapshot().hours().size());
    }

    @Test
    void physicalFilesHoldOnlyRetainedWholeBucketsAfterEviction() throws Exception {
        var store = new FileHistoryStore(dir, mapper, 3, 2, FileHistoryStore.MAX_FILE_BYTES);
        for (int i = 0; i < 7; i++) store.appendMinute(minute(i, 2));
        for (int i = 0; i < 5; i++) {
            store.appendHour(new HourBucket(T0.plusSeconds(3600L * i), 3600, HourBucket.Completeness.COMPLETE, 60,
                    new HourBucket.ActivitySummary(1, false, false, false), List.of(agg(RouteGroup.CRAFTING))));
        }
        List<String> minuteLines = Files.readAllLines(dir.resolve("minute-history.jsonl"));
        List<String> hourLines = Files.readAllLines(dir.resolve("hour-history.jsonl"));
        assertEquals(3, minuteLines.size());
        assertEquals(2, hourLines.size());
        assertTrue(minuteLines.get(0).contains(T0.plusSeconds(240).toString()));
        assertFalse(String.join("", minuteLines).contains(T0.plusSeconds(180).toString()));
        assertTrue(hourLines.get(0).contains(T0.plusSeconds(3 * 3600L).toString()));
    }

    @Test
    void byteBoundDiscardsOldestWholeBucketsAndNeverExceedsCeiling() throws Exception {
        long maxFile = 20_000;
        var store = new FileHistoryStore(dir, mapper, 360, 720, maxFile);
        for (int i = 0; i < 200; i++) store.appendMinute(minute(i, 8));
        var snap = store.snapshot();
        assertTrue(snap.minutes().size() < 200);
        assertTrue(snap.retentionTruncated());
        assertEquals(T0.plusSeconds(60L * (200 - snap.minutes().size())), snap.minutes().get(0).startUtc());
        for (MinuteBucket b : snap.minutes()) assertEquals(8, b.routeGroups().size()); // never split
        long total;
        try (Stream<Path> files = Files.list(dir)) { total = files.mapToLong(p -> p.toFile().length()).sum(); }
        assertTrue(total <= maxFile, "files " + total);
        assertTrue(3 * FileHistoryStore.MAX_FILE_BYTES <= FileHistoryStore.TOTAL_CEILING_BYTES);
    }

    @Test
    void tornAndMalformedLinesAreIgnoredAndFilesAreRestricted() throws Exception {
        var store = new FileHistoryStore(dir, mapper);
        store.appendMinute(minute(0, 1));
        store.appendMinute(minute(1, 1));
        Files.writeString(dir.resolve("minute-history.jsonl"), "not json\n", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(dir.resolve("minute-history.jsonl"), "{\"startUtc\":\"2026-1", java.nio.file.StandardOpenOption.APPEND);
        var again = new FileHistoryStore(dir, mapper);
        assertEquals(2, again.snapshot().minutes().size());
        assertTrue(again.snapshot().available());
        try {
            var perms = Files.getPosixFilePermissions(dir.resolve("minute-history.jsonl"));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        } catch (UnsupportedOperationException ignored) { }
    }

    @Test
    void writeFailureMarksUnavailableAndDoesNotThrowOrLoseMemory() throws Exception {
        Path blocker = Files.writeString(dir.resolve("not-a-dir"), "x");
        var store = new FileHistoryStore(blocker, mapper); // directory path is a regular file
        assertDoesNotThrow(() -> store.appendMinute(minute(0, 1)));
        var snap = store.snapshot();
        assertFalse(snap.available());
        assertEquals(1, snap.minutes().size()); // in-memory retention continues
    }

    @Test
    void unreadableFileIsReportedUnavailableNotEmpty() throws Exception {
        Files.createDirectory(dir.resolve("minute-history.jsonl")); // a directory where a file is expected
        var store = new FileHistoryStore(dir, mapper);
        assertFalse(store.snapshot().available());
    }

    @Test
    void persistedLinesContainNoIdentityOrDynamicPath() throws Exception {
        var store = new FileHistoryStore(dir, mapper);
        store.appendMinute(minute(0, 3));
        String text = Files.readString(dir.resolve("minute-history.jsonl"));
        assertFalse(text.contains("/api"));
        assertFalse(text.toLowerCase().contains("account\""));
        assertTrue(text.contains("\"overflow\":true"));
    }
}
