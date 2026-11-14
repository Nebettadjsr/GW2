package infra.ops;

import application.ops.ForegroundAccounts;
import application.ops.HistoryStore;
import application.ops.HourBucket;
import application.ops.LatencyHistogram;
import application.ops.MinuteBucket;
import application.ops.OperationalPeriodStore;
import application.ops.PeriodDescriptor;
import application.ops.RouteGroup;
import application.ops.RouteGroupAggregate;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Sparse operational activity history (STORY-OPS-003): one versioned CSV file per UTC day that contained an
 * included request, one row per active minute and route group, created lazily and never rewritten. At most
 * {@link #MAX_DAYS} nonempty days of at most {@link #MAX_DAY_BYTES}, and all owned bytes (day files, index and
 * its replacement scratch) within {@link #TOTAL_CEILING_BYTES}; whole oldest days are evicted, never the head
 * of a day. Idle calendar time never deletes, rewrites or creates anything. Weeks are streamed from day files.
 * One application writer; all access is serialized.
 */
public final class SparseActivityStore implements HistoryStore, OperationalPeriodStore {
    public static final int MAX_DAYS = 30;
    public static final long MAX_DAY_BYTES = 2L * 1024 * 1024;
    public static final long TOTAL_CEILING_BYTES = 32L * 1024 * 1024;
    public static final long MAX_INDEX_BYTES = 16L * 1024;
    /** Longest row accepted when reading; the widest possible row is well under half of this. */
    static final int MAX_ROW_BYTES = 1024;
    static final int STREAM_BUFFER_BYTES = 8192;
    static final int SNAPSHOT_MINUTES = 360;

    public static final String HEADER = "schema_version,minute_start_utc,minute_coverage,active_accounts_estimate,"
            + "active_accounts_state,route_group,requests,success,client_errors,server_errors,failed,"
            + "backend_elapsed_micros_sum,backend_elapsed_micros_max,bin_le_25ms,bin_le_50ms,bin_le_100ms,"
            + "bin_le_250ms,bin_le_500ms,bin_le_1000ms,bin_le_2000ms,bin_le_5000ms,bin_gt_5000ms,observed_seconds";
    private static final int FIELDS = 23;
    private static final byte[] HEADER_LINE = (HEADER + "\n").getBytes(StandardCharsets.US_ASCII);
    private static final String INDEX_NAME = "activity-index";
    private static final String INDEX_TEMP = "activity-index.tmp";
    private static final Pattern DAY_FILE = Pattern.compile("activity-(\\d{4}-\\d{2}-\\d{2})\\.csv");
    private static final Pattern LEGACY = Pattern.compile("(minute|hour)-history\\.jsonl(.*\\.tmp)?");
    private static final Pattern OWNED_SCRATCH = Pattern.compile("activity-index\\.tmp");

    private static final class Day {
        long bytes;
        Instant lastMinute;
        EnumSet<RouteGroup> lastGroups = EnumSet.noneOf(RouteGroup.class);
    }

    private final Path dir;
    private final Clock clock;
    private final int maxDays;
    private final long maxDayBytes;
    private final long ceilingBytes;
    private final long maxIndexBytes;
    private final TreeMap<LocalDate, Day> days = new TreeMap<>();
    private final TreeSet<LocalDate> truncatedDays = new TreeSet<>();
    private final TreeSet<LocalDate> lossDays = new TreeSet<>();
    private final TreeSet<LocalDate> prunedWeeks = new TreeSet<>();
    static final int MAX_LOSS_DAYS = 64;
    /** Recognized owned/legacy artifacts that could not be removed; they stay inside the byte bound. */
    private final java.util.HashMap<Path, Long> stuck = new java.util.HashMap<>();
    private boolean metadataUncertain;
    private boolean indexDirty;
    private boolean blocked;
    private boolean readFailed;
    private boolean writeFailed;
    private boolean everLimited;
    private boolean repairedAtStart;

    public SparseActivityStore(Path directory, Clock clock) {
        this(directory, clock, MAX_DAYS, MAX_DAY_BYTES, TOTAL_CEILING_BYTES, MAX_INDEX_BYTES);
    }

    public SparseActivityStore(Path directory, Clock clock, int maxDays, long maxDayBytes, long ceilingBytes,
                               long maxIndexBytes) {
        this.dir = directory;
        this.clock = clock;
        this.maxDays = maxDays;
        this.maxDayBytes = maxDayBytes;
        this.ceilingBytes = ceilingBytes;
        this.maxIndexBytes = maxIndexBytes;
        try {
            load();
        } catch (IOException | RuntimeException e) {
            readFailed = true;
            days.clear();
        }
    }

    // ---- startup ---------------------------------------------------------------------------------------------

    private void load() throws IOException {
        if (!Files.isDirectory(dir)) {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a directory");
            return; // nothing yet; the directory is created by the first durable row only
        }
        TreeMap<LocalDate, Path> candidates = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                var m = DAY_FILE.matcher(name);
                if (LEGACY.matcher(name).matches() || OWNED_SCRATCH.matcher(name).matches()) {
                    if (!deleteQuietly(p)) stuck.put(p, Files.size(p)); // recognized legacy/scratch only; still counted
                } else if (m.matches()) {
                    try { candidates.put(LocalDate.parse(m.group(1)), p); } catch (RuntimeException ignored) { }
                }
            }
        }
        for (var e : candidates.entrySet()) {
            Day day = recover(e.getValue(), e.getKey());
            if (day != null) days.put(e.getKey(), day);
        }
        boolean indexOk = loadIndex();
        if (!days.isEmpty() && !indexOk) {
            // Flags were lost or unreadable: never upgrade surviving days to FULL.
            metadataUncertain = true;
            everLimited = true;
            lossDays.addAll(days.keySet());
        }
        truncatedDays.retainAll(days.keySet());
        trimLoss();
        while (!days.isEmpty() && overBounds(0)) {
            if (!evictOldest(null)) break;
        }
        if (repairedAtStart || (metadataUncertain && !days.isEmpty())) writeIndex();
    }

    /** Validates a day file, trimming a torn final row or bytes past the day cap; deletes files without a header. */
    private Day recover(Path file, LocalDate date) throws IOException {
        Day day = new Day();
        long valid = 0;
        boolean first = true;
        boolean capped = false;
        try (RowReader in = new RowReader(Files.newInputStream(file), Files.size(file))) {
            String line;
            while ((line = in.nextLine()) != null) {
                if (in.consumed() > maxDayBytes) { capped = true; break; }
                if (first) {
                    first = false;
                    if (!line.equals(HEADER)) { if (!deleteQuietly(file)) stuck.put(file, Files.size(file)); return null; }
                } else {
                    MinuteBucket parsed = parse(line);
                    if (parsed == null || parsed.completeness() != MinuteBucket.Completeness.COMPLETE
                            || parsed.foregroundAccounts().state() != ForegroundAccounts.State.COMPLETE) {
                        lossDays.add(date); // skipped malformed rows and incomplete minutes are known loss
                    }
                    Instant minute = rowMinute(line);
                    RouteGroup group = rowGroup(line);
                    if (minute != null && group != null) {
                        if (day.lastMinute == null || minute.isAfter(day.lastMinute)) {
                            day.lastMinute = minute;
                            day.lastGroups = EnumSet.noneOf(RouteGroup.class);
                        }
                        if (minute.equals(day.lastMinute)) day.lastGroups.add(group);
                    }
                }
                valid = in.consumed();
            }
        }
        if (first) { if (!deleteQuietly(file)) stuck.put(file, Files.size(file)); return null; }
        if (capped) truncatedDays.add(date);
        if (Files.size(file) > valid) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) { ch.truncate(valid); }
            if (!capped) lossDays.add(date); // a torn final row is a known persistence loss for that day
            repairedAtStart = true;
        }
        day.bytes = valid;
        return day;
    }

    /** Reads the advisory index; false when it is missing, unreadable, oversized or has malformed lines. */
    private boolean loadIndex() {
        Path index = dir.resolve(INDEX_NAME);
        try {
            if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.size(index) > maxIndexBytes) {
                if (!deleteQuietly(index)) stuck.put(index, Files.size(index));
                return false;
            }
            boolean ok = true;
            for (String line : Files.readAllLines(index, StandardCharsets.US_ASCII)) {
                String[] f = line.split(",");
                if (f.length != 2) { ok = false; continue; }
                try {
                    LocalDate d = LocalDate.parse(f[1]);
                    switch (f[0]) {
                        case "T" -> truncatedDays.add(d);
                        case "L" -> lossDays.add(d);
                        case "P" -> prunedWeeks.add(d);
                        default -> ok = false;
                    }
                } catch (RuntimeException e) { ok = false; }
            }
            return ok;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Keeps loss metadata bounded by dropping the oldest flagged days. */
    private void trimLoss() {
        while (lossDays.size() > MAX_LOSS_DAYS) {
            LocalDate discard = lossDays.stream().filter(d -> !days.containsKey(d)).findFirst().orElse(null);
            if (discard == null) break; // retained-day flags stay attached to their surviving rows
            lossDays.remove(discard);
        }
    }

    // ---- writing ---------------------------------------------------------------------------------------------

    @Override
    public synchronized void appendMinute(MinuteBucket bucket) {
        LocalDate date = null;
        try {
            if (readFailed || blocked || bucket == null || bucket.routeGroups().isEmpty()) return;
            Instant minute = bucket.startUtc();
            date = minute.atZone(ZoneOffset.UTC).toLocalDate();
            Day newest = days.isEmpty() ? null : days.lastEntry().getValue();
            if (newest != null && newest.lastMinute != null && minute.isBefore(newest.lastMinute)) return;
            boolean sameMinute = newest != null && minute.equals(newest.lastMinute);
            StringBuilder rows = new StringBuilder();
            EnumSet<RouteGroup> written = EnumSet.noneOf(RouteGroup.class);
            for (RouteGroupAggregate a : bucket.routeGroups()) {
                if (sameMinute && newest.lastGroups.contains(a.group())) continue; // already durable
                if (!written.add(a.group())) continue;
                rows.append(row(bucket, a)).append('\n');
            }
            if (rows.length() == 0) return;
            byte[] data = rows.toString().getBytes(StandardCharsets.US_ASCII);
            Day day = days.get(date);
            if (day != null && truncatedDays.contains(date)) return;
            long needed = (day == null ? HEADER_LINE.length : 0) + data.length;
            if ((day == null ? 0 : day.bytes) + needed > maxDayBytes) {
                if (day != null) flag(truncatedDays, date);
                return;
            }
            if (day == null) {
                while (days.size() >= maxDays) if (!evictOldest(date)) return;
            }
            while (overBounds(needed)) {
                if (!evictOldest(date)) {
                    if (day != null) flag(truncatedDays, date);
                    return;
                }
            }
            write(date, day, data);
            boolean created = day == null;
            day = days.get(date);
            day.lastMinute = minute;
            if (!sameMinute) day.lastGroups = EnumSet.noneOf(RouteGroup.class);
            day.lastGroups.addAll(written);
            if (bucket.completeness() != MinuteBucket.Completeness.COMPLETE
                    || bucket.foregroundAccounts().state() != ForegroundAccounts.State.COMPLETE) {
                flag(lossDays, date);
            }
            if (created || indexDirty) writeIndex(); // a present index distinguishes "no flags" from "flags lost"
            if (!indexDirty) writeFailed = false;
        } catch (IOException | RuntimeException e) {
            writeFailed = true;
            if (date != null) flag(lossDays, date); // kept (bounded) even before the day exists, until restart
        }
    }

    private void write(LocalDate date, Day existing, byte[] data) throws IOException {
        Path file = dayFile(date);
        if (existing == null) {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            byte[] all = new byte[HEADER_LINE.length + data.length];
            System.arraycopy(HEADER_LINE, 0, all, 0, HEADER_LINE.length);
            System.arraycopy(data, 0, all, HEADER_LINE.length, data.length);
            try {
                Files.write(file, all, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrict(file, "rw-------");
            } catch (IOException e) {
                deleteQuietly(file);
                throw e;
            }
            Day day = new Day();
            day.bytes = all.length;
            days.put(date, day);
        } else {
            try {
                Files.write(file, data, StandardOpenOption.APPEND);
                existing.bytes += data.length;
            } catch (IOException e) {
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    ch.truncate(existing.bytes); // a partial append must not corrupt later rows
                } catch (IOException ignored) { /* the next restart repairs it */ }
                throw e;
            }
        }
    }

    /** True when owned bytes (files, reserved index and its scratch replacement) plus {@code extra} exceed bounds. */
    private boolean overBounds(long extra) {
        long files = 0;
        for (Day d : days.values()) files += d.bytes;
        for (long b : stuck.values()) files += b;
        return days.size() > maxDays || files + extra + 2 * maxIndexBytes > ceilingBytes;
    }

    /**
     * Removes the whole oldest retained day (never {@code keep}); returns false when none can go. Accounting
     * changes only after the file is confirmed gone; a failed unlink stops all admission and reports failure.
     */
    private boolean evictOldest(LocalDate keep) {
        for (LocalDate d : days.keySet()) {
            if (d.equals(keep)) continue;
            if (!deleteQuietly(dayFile(d))) {
                blocked = true;
                writeFailed = true;
                return false;
            }
            days.remove(d);
            truncatedDays.remove(d);
            lossDays.remove(d);
            everLimited = true;
            LocalDate week = weekStart(d);
            if (days.keySet().stream().anyMatch(x -> weekStart(x).equals(week))) prunedWeeks.add(week);
            prunedWeeks.removeIf(w -> days.keySet().stream().noneMatch(x -> weekStart(x).equals(w)));
            writeIndex();
            return true;
        }
        return false;
    }

    private void flag(TreeSet<LocalDate> set, LocalDate date) {
        if (set.add(date)) {
            if (set == lossDays) trimLoss();
            writeIndex();
        }
    }

    /** Rewrites the small index through one fixed scratch name; its worst case is reserved in the ceiling. */
    private void writeIndex() {
        try {
            TreeMap<LocalDate, List<String>> byDate = new TreeMap<>(java.util.Comparator.reverseOrder());
            for (LocalDate d : truncatedDays) byDate.computeIfAbsent(d, k -> new ArrayList<>()).add("T," + d);
            for (LocalDate d : lossDays) byDate.computeIfAbsent(d, k -> new ArrayList<>()).add("L," + d);
            for (LocalDate d : prunedWeeks) byDate.computeIfAbsent(d, k -> new ArrayList<>()).add("P," + d);
            StringBuilder text = new StringBuilder();
            outer:
            for (List<String> lines : byDate.values()) { // newest first, so trimming drops the oldest flags
                for (String l : lines) {
                    if (text.length() + l.length() + 1 > maxIndexBytes) break outer;
                    text.append(l).append('\n');
                }
            }
            byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path temp = dir.resolve(INDEX_TEMP);
            Path target = dir.resolve(INDEX_NAME);
            try {
                Files.write(temp, bytes);
                restrict(temp, "rw-------");
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            indexDirty = false;
        } catch (IOException | RuntimeException e) {
            indexDirty = true;
            writeFailed = true;
        }
    }

    private static String row(MinuteBucket m, RouteGroupAggregate a) {
        ForegroundAccounts fa = m.foregroundAccounts();
        StringBuilder sb = new StringBuilder(256);
        sb.append("1,").append(m.startUtc()).append(',').append(m.completeness()).append(',')
                .append(fa.reported() == null ? "" : fa.reported().toString()).append(',').append(fa.state())
                .append(',').append(a.group()).append(',').append(a.requests()).append(',').append(a.success())
                .append(',').append(a.clientError()).append(',').append(a.serverError()).append(',')
                .append(a.failed()).append(',').append(a.elapsedMicrosSum()).append(',')
                .append(a.elapsedMicrosMax());
        for (long b : LatencyHistogram.toCounts(a.histogram())) sb.append(',').append(b);
        sb.append(',').append(Math.max(0, Math.min(60, m.observedSeconds())));
        return sb.toString();
    }

    // ---- reading rows ----------------------------------------------------------------------------------------

    private static Instant rowMinute(String line) {
        String[] f = line.split(",", -1);
        if (f.length != FIELDS || !f[0].equals("1")) return null;
        try { return Instant.parse(f[1]); } catch (RuntimeException e) { return null; }
    }

    private static RouteGroup rowGroup(String line) {
        String[] f = line.split(",", -1);
        if (f.length != FIELDS) return null;
        try { return RouteGroup.valueOf(f[5]); } catch (RuntimeException e) { return null; }
    }

    /** Parses one stored row; null when malformed. */
    private static MinuteBucket parse(String line) {
        try {
            String[] f = line.split(",", -1);
            if (f.length != FIELDS || !f[0].equals("1")) return null;
            ForegroundAccounts.State state = ForegroundAccounts.State.valueOf(f[4]);
            Integer est = f[3].isEmpty() ? null : Integer.valueOf(f[3]);
            ForegroundAccounts fa = state == ForegroundAccounts.State.COMPLETE
                    ? new ForegroundAccounts(state, est, null) : new ForegroundAccounts(state, null, est);
            long[] bins = new long[LatencyHistogram.BINS];
            for (int i = 0; i < bins.length; i++) bins[i] = Long.parseLong(f[13 + i]);
            RouteGroupAggregate a = new RouteGroupAggregate(RouteGroup.valueOf(f[5]), Long.parseLong(f[6]),
                    Long.parseLong(f[7]), Long.parseLong(f[8]), Long.parseLong(f[9]), Long.parseLong(f[10]),
                    Long.parseLong(f[11]), Long.parseLong(f[12]), LatencyHistogram.toBins(bins));
            MinuteBucket.Completeness c = MinuteBucket.Completeness.valueOf(f[2]);
            int observed = Integer.parseInt(f[22]);
            if (observed < 0 || observed > 60) return null;
            return new MinuteBucket(Instant.parse(f[1]), observed, c, fa,
                    List.of(a));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Bounded line reader: lines over {@link #MAX_ROW_BYTES} are skipped and an unterminated tail is dropped. */
    static final class RowReader implements AutoCloseable {
        private final InputStream in;
        private long remaining;
        private long consumed;
        private final byte[] line = new byte[MAX_ROW_BYTES];

        RowReader(InputStream in, long limit) {
            this.in = new BufferedInputStream(in, STREAM_BUFFER_BYTES);
            this.remaining = limit;
        }

        /** Offset just after the last line returned (including skipped over-long ones). */
        long consumed() { return consumed; }

        String nextLine() throws IOException {
            int n = 0;
            boolean overflow = false;
            long start = consumed;
            while (remaining > 0) {
                int b = in.read();
                if (b < 0) break;
                remaining--;
                consumed++;
                if (b == '\n') {
                    if (overflow) { n = 0; overflow = false; start = consumed; continue; }
                    return new String(line, 0, n, StandardCharsets.US_ASCII);
                }
                if (n < line.length) line[n++] = (byte) b; else overflow = true;
            }
            consumed = start; // an unterminated tail is not consumed
            return null;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    // ---- HistoryStore (summary compatibility) ----------------------------------------------------------------

    @Override
    public synchronized Snapshot snapshot() {
        List<MinuteBucket> out = new ArrayList<>();
        try {
            if (!readFailed) {
                for (var e : days.descendingMap().entrySet()) {
                    List<MinuteBucket> dayMinutes = new ArrayList<>();
                    try (RowReader in = new RowReader(Files.newInputStream(dayFile(e.getKey())), e.getValue().bytes)) {
                        in.nextLine();
                        String line;
                        while ((line = in.nextLine()) != null) {
                            MinuteBucket b = parse(line);
                            if (b == null) continue;
                            int last = dayMinutes.size() - 1;
                            if (last >= 0 && dayMinutes.get(last).startUtc().equals(b.startUtc())) {
                                MinuteBucket prev = dayMinutes.get(last);
                                var groups = new ArrayList<>(prev.routeGroups());
                                groups.addAll(b.routeGroups());
                                dayMinutes.set(last, new MinuteBucket(prev.startUtc(), prev.observedSeconds(),
                                        prev.completeness(), prev.foregroundAccounts(), List.copyOf(groups)));
                            } else {
                                dayMinutes.add(b);
                            }
                        }
                    }
                    out.addAll(0, dayMinutes);
                    if (out.size() >= SNAPSHOT_MINUTES) break;
                }
            }
        } catch (IOException | RuntimeException e) {
            readFailed = true;
        }
        while (out.size() > SNAPSHOT_MINUTES) out.remove(0);
        boolean limited = everLimited || !truncatedDays.isEmpty() || !prunedWeeks.isEmpty();
        return new Snapshot(!readFailed && !writeFailed, limited, List.copyOf(out), List.<HourBucket>of());
    }

    // ---- OperationalPeriodStore ------------------------------------------------------------------------------

    private static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private Path dayFile(LocalDate d) { return dir.resolve("activity-" + d + ".csv"); }

    private static Instant at(LocalDate d) { return d.atStartOfDay().toInstant(ZoneOffset.UTC); }

    private static String id(char kind, LocalDate d) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((kind + ":" + d).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public synchronized Periods periods() {
        if (readFailed || blocked || writeFailed && days.isEmpty()) return Periods.unavailable();
        return new Periods(true, listPeriods());
    }

    @Override
    public synchronized List<PeriodDescriptor> listPeriods() {
        if (readFailed) return List.of();
        Instant now = clock.instant();
        List<PeriodDescriptor> out = new ArrayList<>();
        TreeSet<LocalDate> weeks = new TreeSet<>();
        for (LocalDate d : days.keySet()) weeks.add(weekStart(d));
        for (LocalDate w : weeks.descendingSet()) {
            out.add(week(w, now));
            for (LocalDate d : days.descendingKeySet()) if (weekStart(d).equals(w)) out.add(day(d, now));
        }
        return List.copyOf(out);
    }

    private PeriodDescriptor day(LocalDate d, Instant now) {
        boolean closed = !now.isBefore(at(d.plusDays(1)));
        boolean truncated = truncatedDays.contains(d);
        boolean full = closed && !truncated && !lossDays.contains(d);
        return new PeriodDescriptor(id('d', d), PeriodDescriptor.Kind.DAY, at(d), at(d.plusDays(1)),
                closed ? PeriodDescriptor.State.CLOSED : PeriodDescriptor.State.OPEN,
                full ? PeriodDescriptor.Coverage.FULL : PeriodDescriptor.Coverage.PARTIAL, truncated, false,
                days.get(d).bytes, "gw2-activity-" + d + ".csv");
    }

    private PeriodDescriptor week(LocalDate w, Instant now) {
        boolean closed = !now.isBefore(at(w.plusDays(7)));
        boolean truncated = false;
        boolean loss = !lossDays.subSet(w, true, w.plusDays(7), false).isEmpty();
        long bytes = 0;
        int count = 0;
        for (var e : days.subMap(w, true, w.plusDays(6), true).entrySet()) {
            truncated |= truncatedDays.contains(e.getKey());
            loss |= lossDays.contains(e.getKey());
            bytes += e.getValue().bytes;
            count++;
        }
        bytes -= (long) Math.max(0, count - 1) * HEADER_LINE.length;
        boolean pruned = prunedWeeks.contains(w);
        boolean full = closed && !truncated && !loss && !pruned;
        String name = String.format("gw2-activity-week-%d-W%02d.csv", w.get(IsoFields.WEEK_BASED_YEAR),
                w.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        return new PeriodDescriptor(id('w', w), PeriodDescriptor.Kind.WEEK, at(w), at(w.plusDays(7)),
                closed ? PeriodDescriptor.State.CLOSED : PeriodDescriptor.State.OPEN,
                full ? PeriodDescriptor.Coverage.FULL : PeriodDescriptor.Coverage.PARTIAL, truncated, pruned,
                bytes, name);
    }

    @Override
    public synchronized Download openDownload(String selectionId) {
        if (readFailed) return Download.unavailable();
        PeriodDescriptor wanted = null;
        if (selectionId != null) {
            for (PeriodDescriptor p : listPeriods()) if (p.selectionId().equals(selectionId)) wanted = p;
        }
        if (wanted == null) return Download.notFound();
        List<RowReader> sources = new ArrayList<>();
        try {
            LocalDate from = wanted.fromUtc().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate last = wanted.toUtc().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
            // Every file is opened (and its length fixed) now, so a later append or eviction cannot change it.
            for (var e : days.subMap(from, true, last, true).entrySet()) {
                sources.add(new RowReader(Files.newInputStream(dayFile(e.getKey())), e.getValue().bytes));
            }
        } catch (IOException | RuntimeException e) {
            for (RowReader s : sources) closeQuietly(s);
            return Download.unavailable();
        }
        return new Download(Status.OK, wanted, new PeriodStream(sources, wanted.fromUtc(), wanted.toUtc()));
    }

    private static void closeQuietly(RowReader r) {
        try { r.close(); } catch (IOException ignored) { /* nothing to do */ }
    }

    /** Streams one header and the in-bounds rows of the selected days chronologically through a fixed buffer. */
    private static final class PeriodStream extends InputStream {
        private final List<RowReader> sources;
        private final Instant from, to;
        private final byte[] out = new byte[STREAM_BUFFER_BYTES];
        private int pos, len, current;
        private boolean headerSent, currentHeaderSkipped, done;

        PeriodStream(List<RowReader> sources, Instant from, Instant to) {
            this.sources = sources;
            this.from = from;
            this.to = to;
        }

        private boolean fill() throws IOException {
            pos = 0;
            len = 0;
            if (done) return false;
            if (!headerSent) {
                System.arraycopy(HEADER_LINE, 0, out, 0, HEADER_LINE.length);
                len = HEADER_LINE.length;
                headerSent = true;
            }
            while (len + MAX_ROW_BYTES + 1 <= out.length && current < sources.size()) {
                String line = sources.get(current).nextLine();
                if (line == null) {
                    closeQuietly(sources.get(current));
                    current++;
                    currentHeaderSkipped = false;
                    continue;
                }
                if (!currentHeaderSkipped) { currentHeaderSkipped = true; continue; }
                Instant minute = rowMinute(line);
                if (minute == null || rowGroup(line) == null || minute.isBefore(from) || !minute.isBefore(to)) continue;
                byte[] b = line.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(b, 0, out, len, b.length);
                len += b.length;
                out[len++] = '\n';
            }
            if (current >= sources.size()) done = true;
            return len > 0;
        }

        @Override public int read() throws IOException {
            if (pos >= len && !fill()) return -1;
            return out[pos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) return 0;
            if (pos >= len && !fill()) return -1;
            int c = Math.min(n, len - pos);
            System.arraycopy(out, pos, b, off, c);
            pos += c;
            return c;
        }

        @Override public void close() {
            done = true;
            len = pos = 0;
            for (RowReader s : sources) closeQuietly(s);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** True only when the file is confirmed absent afterwards. */
    private static boolean deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); return !Files.exists(p, LinkOption.NOFOLLOW_LINKS); } catch (IOException e) { return false; }
    }

    private static void restrict(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) { /* non-POSIX filesystem */ }
    }
}
