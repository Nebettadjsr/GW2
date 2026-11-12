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

    /** One planned journey start. {@code offsetMillis} is the scheduled time from window start, independent of any response. */
    public record ScheduledStart(int user, long offsetMillis, String journey) { }

    /**
     * Seeded open-loop schedule: each user has an independent random phase, so users do not start
     * in an identical burst. A crafting burst (profit + recipe detail) is followed by the in-game
     * pause; account browsing uses the think-time range. Local-only actions (checkboxes, sorting,
     * Profit search/limit, tab inspection) deliberately generate no starts. Offered independently
     * of response completion so slowdowns cannot reduce offered load.
     */
    public List<ScheduledStart> plan(int users, long seed, long windowMillis, List<String> enabled) {
        if (users < 1) throw new IllegalArgumentException("users must be >= 1");
        List<String> account = ACCOUNT_JOURNEYS.stream().filter(enabled::contains).toList();
        List<String> crafting = CRAFTING_JOURNEYS.stream().filter(enabled::contains).toList();
        boolean status = enabled.contains(STATUS_JOURNEY);
        java.util.ArrayList<ScheduledStart> out = new java.util.ArrayList<>();
        for (int u = 0; u < users; u++) {
            Random r = new Random(seed * 1_000_003L + u);
            long t = (long) (r.nextDouble() * browseMaxMs); // random phase
            while (t < windowMillis) {
                boolean craft = !crafting.isEmpty() && (account.isEmpty() || r.nextInt(100) >= accountPercent);
                if (craft) {
                    for (String j : crafting) { out.add(new ScheduledStart(u, t, j)); t += 1_500 + r.nextInt(2_000); }
                    t += between(r, pauseMinMs, pauseMaxMs);
                } else if (!account.isEmpty()) {
                    out.add(new ScheduledStart(u, t, account.get(r.nextInt(account.size()))));
                    if (status && r.nextInt(10) == 0) out.add(new ScheduledStart(u, t + 500, STATUS_JOURNEY));
                    t += between(r, browseMinMs, browseMaxMs);
                } else {
                    break;
                }
            }
        }
        out.removeIf(s -> s.offsetMillis() >= windowMillis);
        out.sort(java.util.Comparator.comparingLong(ScheduledStart::offsetMillis).thenComparingInt(ScheduledStart::user));
        return List.copyOf(out);
    }

    private static long between(Random r, long min, long max) {
        return min + (long) (r.nextDouble() * (max - min));
    }
}
