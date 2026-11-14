package application.ops;

import java.time.Instant;
import java.util.List;

/**
 * One closed UTC hour merged from its available minute buckets. Distinct accounts are not additive, so
 * only a per-minute activity summary is kept; no hourly distinct-account count is ever claimed.
 */
public record HourBucket(Instant startUtc, int observedSeconds, Completeness completeness, int minuteBuckets,
                         ActivitySummary activity, List<RouteGroupAggregate> routeGroups) {
    public enum Completeness { COMPLETE, PARTIAL }

    /** @param maxReportedActive greatest per-minute estimate (exact or lower bound), null when none reported */
    public record ActivitySummary(Integer maxReportedActive, boolean anyPartialStartup, boolean anyTruncated,
                                  boolean anyUnavailable) {}

    public static HourBucket rollUp(Instant hourStart, List<MinuteBucket> minutes) {
        int seconds = 0;
        Integer max = null;
        boolean partial = false, truncated = false, unavailable = false;
        java.util.EnumMap<RouteGroup, List<RouteGroupAggregate>> byGroup = new java.util.EnumMap<>(RouteGroup.class);
        for (MinuteBucket m : minutes) {
            seconds += m.observedSeconds();
            Integer reported = m.foregroundAccounts().reported();
            if (reported != null && (max == null || reported > max)) max = reported;
            switch (m.foregroundAccounts().state()) {
                case PARTIAL_STARTUP -> partial = true;
                case TRUNCATED -> truncated = true;
                case UNAVAILABLE -> unavailable = true;
                case COMPLETE -> { }
            }
            for (RouteGroupAggregate a : m.routeGroups()) {
                byGroup.computeIfAbsent(a.group(), g -> new java.util.ArrayList<>()).add(a);
            }
        }
        List<RouteGroupAggregate> merged = new java.util.ArrayList<>();
        byGroup.forEach((g, parts) -> merged.add(RouteGroupAggregate.merge(parts, g)));
        boolean complete = minutes.size() >= 60 && minutes.stream()
                .allMatch(m -> m.completeness() == MinuteBucket.Completeness.COMPLETE);
        return new HourBucket(hourStart, Math.min(seconds, 3600),
                complete ? Completeness.COMPLETE : Completeness.PARTIAL, minutes.size(),
                new ActivitySummary(max, partial, truncated, unavailable), List.copyOf(merged));
    }
}
