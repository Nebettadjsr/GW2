package application.ops;

import java.time.Instant;
import java.util.List;

/** One closed UTC minute. A minute with coverage and no traffic is a zero bucket; an absent one is a gap. */
public record MinuteBucket(Instant startUtc, int observedSeconds, Completeness completeness,
                           ForegroundAccounts foregroundAccounts, List<RouteGroupAggregate> routeGroups) {
    public enum Completeness { COMPLETE, PARTIAL }
}
