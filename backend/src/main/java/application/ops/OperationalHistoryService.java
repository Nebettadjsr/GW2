package application.ops;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The collection facade of STORY-OPS-001: request observation, foreground activity and the once-per-minute
 * flush into bounded history. Telemetry never throws to the caller, so a business request cannot fail on it.
 */
public final class OperationalHistoryService {
    private final ForegroundActivityTracker activity;
    private final RequestAggregator aggregator;
    private final HistoryStore store;
    private final OperationalPeriodStore periods;

    public OperationalHistoryService(Clock clock, ForegroundActivityTracker activity, HistoryStore store) {
        this(clock, activity, store, OperationalPeriodStore.EMPTY);
    }

    public OperationalHistoryService(Clock clock, ForegroundActivityTracker activity, HistoryStore store,
                                     OperationalPeriodStore periods) {
        this.activity = activity;
        this.aggregator = new RequestAggregator(clock, activity::snapshotAt);
        this.store = store;
        this.periods = periods;
    }

    /** In-memory only: bumps fixed counters and, for an eligible verified account, its activity timestamp. */
    public void observe(String method, String path, Optional<String> verifiedAccountId, int status,
                        boolean failedResponse, long elapsedNanos) {
        try {
            Optional<RouteGroup> group = RouteClassifier.classify(method, path);
            if (group.isEmpty()) return;
            aggregator.record(group.get(), status, failedResponse, elapsedNanos / 1_000);
            if (group.get().foreground() && verifiedAccountId.isPresent()) {
                try {
                    activity.record(UUID.fromString(verifiedAccountId.get().trim()));
                } catch (IllegalArgumentException notAGuid) {
                    // Not a verified GUID: not countable.
                }
            }
        } catch (RuntimeException telemetryFailure) {
            // Telemetry must never fail the request.
        }
    }

    public ForegroundAccounts currentActivity() { return activity.snapshot(); }

    /** The scheduled once-a-minute step: closes elapsed minutes (also without later traffic) and persists them. */
    public synchronized void flush() {
        try {
            for (MinuteBucket bucket : aggregator.drainClosed()) store.appendMinute(bucket);
        } catch (RuntimeException telemetryFailure) {
            // Persistence problems surface as an unavailable history state, never as a failed request.
        }
    }

    public HistoryStore.Snapshot history() { return store.snapshot(); }

    /** Closes elapsed minutes first so a day or week advances at its UTC boundary without further traffic. */
    public List<PeriodDescriptor> listPeriods() {
        flush();
        try {
            return periods.listPeriods();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    public OperationalPeriodStore.Download openDownload(String selectionId) {
        flush();
        try {
            return periods.openDownload(selectionId);
        } catch (RuntimeException failure) {
            return OperationalPeriodStore.Download.unavailable();
        }
    }
}
