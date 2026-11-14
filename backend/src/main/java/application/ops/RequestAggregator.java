package application.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bounded in-memory request aggregation (STORY-OPS-001 sections 3-4): a request only bumps fixed
 * counters of the open minute. Closing a minute (by the scheduled flush or the first request of the next
 * minute) queues one bucket; nothing is written per request.
 */
public final class RequestAggregator {
    static final int MAX_PENDING = 30;

    private static final class Counters {
        long requests, success, client, server, failed, sum, max;
        final long[] bins = new long[LatencyHistogram.BINS];
    }

    private final Clock clock;
    private final Supplier<ForegroundAccounts> activity;
    private final EnumMap<RouteGroup, Counters> open = new EnumMap<>(RouteGroup.class);
    private final List<MinuteBucket> pending = new ArrayList<>();
    private Instant openMinute;
    private Instant observedFrom;

    public RequestAggregator(Clock clock, Supplier<ForegroundAccounts> activity) {
        this.clock = clock;
        this.activity = activity;
        Instant now = clock.instant();
        this.openMinute = now.truncatedTo(ChronoUnit.MINUTES);
        this.observedFrom = now;
    }

    /** @param status HTTP status, or a value outside 200-599 when no complete response was produced */
    public synchronized void record(RouteGroup group, int status, boolean failedResponse, long elapsedMicros) {
        roll();
        Counters c = open.computeIfAbsent(group, g -> new Counters());
        long elapsed = Math.max(0, elapsedMicros);
        c.requests = LatencyHistogram.saturatedAdd(c.requests, 1);
        if (failedResponse || status < 200 || status > 599) c.failed++;
        else if (status < 400) c.success++;
        else if (status < 500) c.client++;
        else c.server++;
        c.sum = LatencyHistogram.saturatedAdd(c.sum, elapsed);
        c.max = Math.max(c.max, elapsed);
        c.bins[LatencyHistogram.binFor(elapsed)]++;
    }

    /** Closes every elapsed minute and returns (and clears) the closed buckets awaiting persistence. */
    public synchronized List<MinuteBucket> drainClosed() {
        roll();
        List<MinuteBucket> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    private void roll() {
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        if (minute.isBefore(openMinute)) {
            // Clock moved backwards: the open minute is unreliable, so drop it and observe afresh.
            open.clear();
            openMinute = minute;
            observedFrom = now;
            return;
        }
        if (minute.equals(openMinute)) return;
        ForegroundAccounts state = activity.get();
        boolean first = true;
        for (Instant m = openMinute; m.isBefore(minute); m = m.plus(Duration.ofMinutes(1))) {
            int covered = first ? (int) Math.min(60, Math.max(0,
                    Duration.between(observedFrom, m.plusSeconds(60)).toSeconds())) : 60;
            List<RouteGroupAggregate> groups = new ArrayList<>();
            if (first) {
                open.forEach((g, c) -> groups.add(new RouteGroupAggregate(g, c.requests, c.success, c.client,
                        c.server, c.failed, c.sum, c.max, LatencyHistogram.toBins(c.bins))));
            }
            // Only the minute that just closed has a trustworthy activity reading; later catch-up minutes do not.
            pending.add(new MinuteBucket(m, covered,
                    covered >= 60 ? MinuteBucket.Completeness.COMPLETE : MinuteBucket.Completeness.PARTIAL,
                    first ? state : ForegroundAccounts.unavailable(), List.copyOf(groups)));
            first = false;
        }
        while (pending.size() > MAX_PENDING) pending.remove(0);
        open.clear();
        openMinute = minute;
    }
}
