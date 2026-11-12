package perf;

import java.util.List;
import java.util.Random;

/**
 * Cadence/mix sensitivity profiles. These are disclosed <em>assumptions</em>, not measured averages
 * (the research found methodology and contextual dwell time, not this application's click rate).
 */
public enum WorkloadProfile {
    BROWSE_HEAVY("browse-heavy", 15_000, 45_000, 300_000, 600_000, 80),
    BALANCED("balanced", 30_000, 90_000, 180_000, 480_000, 50),
    CRAFTING_CONTEXT("crafting-context", 45_000, 120_000, 120_000, 300_000, 25);

    public static final List<String> ACCOUNT_JOURNEYS = List.of("bank", "materials", "characters", "character-core", "character-inventory");
    public static final List<String> CRAFTING_JOURNEYS = List.of("crafting-profit", "recipe-detail");
    public static final String STATUS_JOURNEY = "system-status";

    private final String id;
    private final long browseMinMs, browseMaxMs, pauseMinMs, pauseMaxMs;
    private final int accountPercent;

    WorkloadProfile(String id, long browseMinMs, long browseMaxMs, long pauseMinMs, long pauseMaxMs, int accountPercent) {
        this.id = id;
        this.browseMinMs = browseMinMs;
        this.browseMaxMs = browseMaxMs;
        this.pauseMinMs = pauseMinMs;
        this.pauseMaxMs = pauseMaxMs;
        this.accountPercent = accountPercent;
    }

    public String id() { return id; }
    public int accountPercent() { return accountPercent; }
    public long browseMinMs() { return browseMinMs; }
    public long browseMaxMs() { return browseMaxMs; }
    public long pauseMinMs() { return pauseMinMs; }
    public long pauseMaxMs() { return pauseMaxMs; }

    public static WorkloadProfile parse(String id) {
        for (WorkloadProfile p : values()) if (p.id.equals(id)) return p;
        throw new IllegalArgumentException("unknown profile " + id);
    }

    /** Mounted Status page poll interval (frontend default), per actor while it stays on Status. */
    public static final long STATUS_POLL_MS = 5_000;
    /** The documented crafting burst: all PROFIT requests fall inside one minute. */
    public static final long BURST_SPAN_MS = 60_000;

    /**
     * One planned start. {@code offsetMillis} is the scheduled time from window start, independent of any response.
     * {@code poll} marks a background Status poll (counted separately from user journeys).
     */
    public record ScheduledStart(int user, long offsetMillis, String journey, boolean poll) {
        public ScheduledStart(int user, long offsetMillis, String journey) { this(user, offsetMillis, journey, false); }
    }

    public List<ScheduledStart> plan(int users, long seed, long windowMillis, List<String> enabled) {
        return plan(users, seed, 0, windowMillis, enabled, CapacityConfig.DEFAULT_BURST_MIN, CapacityConfig.DEFAULT_BURST_MAX);
    }

    /**
     * Seeded open-loop schedule. Each user owns ONE continuous, seeded timeline (random initial phase, then
     * workflow after workflow); a measured window is the slice {@code [windowStart, windowStart+window)} of that
     * timeline, with offsets relative to the slice start. Adjacent windows therefore continue each user's
     * browse/idle phase instead of restarting every user at once. A crafting workflow is
     * {@code burstMin..burstMax} PROFIT requests spread within one minute, then one recipe detail, then the
     * in-game pause. Account browsing uses the think-time range; a Status visit adds mounted 5 s polls until
     * the actor navigates away. Local-only actions (checkboxes, sorting, Profit search/limit, tab inspection)
     * deliberately generate no starts.
     */
    public List<ScheduledStart> plan(int users, long seed, long windowStart, long windowMillis, List<String> enabled,
                                     int burstMin, int burstMax) {
        if (users < 1) throw new IllegalArgumentException("users must be >= 1");
        if (burstMin < 1 || burstMax < burstMin) throw new IllegalArgumentException("invalid burst range");
        long end = windowStart + windowMillis;
        boolean profit = enabled.contains("crafting-profit");
        boolean detail = enabled.contains("recipe-detail");
        boolean canCraft = profit || detail;
        List<String> account = ACCOUNT_JOURNEYS.stream().filter(enabled::contains).toList();
        boolean status = enabled.contains(STATUS_JOURNEY);
        boolean canBrowse = !account.isEmpty() || status;
        java.util.ArrayList<ScheduledStart> out = new java.util.ArrayList<>();
        for (int u = 0; u < users; u++) {
            Random r = new Random(seed * 1_000_003L + u);
            long t = (long) (r.nextDouble() * Math.max(browseMaxMs, pauseMaxMs)); // random initial phase
            while (t < end) {
                boolean craft = canCraft && (!canBrowse || r.nextInt(100) >= accountPercent);
                if (craft) {
                    long burstEnd = t;
                    if (profit) {
                        int n = burstMin + r.nextInt(burstMax - burstMin + 1);
                        long[] offs = new long[n];
                        for (int i = 0; i < n; i++) offs[i] = t + (long) (r.nextDouble() * BURST_SPAN_MS);
                        java.util.Arrays.sort(offs);
                        for (long o : offs) add(out, new ScheduledStart(u, o, "crafting-profit"), windowStart, end);
                        burstEnd = offs[n - 1];
                    }
                    if (detail) {
                        burstEnd += 1_500 + r.nextInt(2_000);
                        add(out, new ScheduledStart(u, burstEnd, "recipe-detail"), windowStart, end);
                    }
                    t = burstEnd + between(r, pauseMinMs, pauseMaxMs);
                } else if (canBrowse) {
                    long think = between(r, browseMinMs, browseMaxMs);
                    boolean visitStatus = status && (account.isEmpty() || r.nextInt(10) == 0);
                    if (visitStatus) {
                        add(out, new ScheduledStart(u, t, STATUS_JOURNEY), windowStart, end);
                        for (long p = t + STATUS_POLL_MS; p < t + think; p += STATUS_POLL_MS) {
                            add(out, new ScheduledStart(u, p, STATUS_JOURNEY, true), windowStart, end);
                        }
                    } else {
                        add(out, new ScheduledStart(u, t, account.get(r.nextInt(account.size()))), windowStart, end);
                    }
                    t += think;
                } else {
                    break;
                }
            }
        }
        out.sort(java.util.Comparator.comparingLong(ScheduledStart::offsetMillis).thenComparingInt(ScheduledStart::user)
                .thenComparing(ScheduledStart::journey));
        return List.copyOf(out);
    }

    private static void add(List<ScheduledStart> out, ScheduledStart s, long from, long to) {
        if (s.offsetMillis() >= from && s.offsetMillis() < to) {
            out.add(new ScheduledStart(s.user(), s.offsetMillis() - from, s.journey(), s.poll()));
        }
    }

    private static long between(Random r, long min, long max) {
        return min + (long) (r.nextDouble() * (max - min));
    }
}
