package application.ops;

import application.access.AccountAccessService;
import application.access.OwnerPolicy;
import application.access.AccountSessions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Owner-authorized summary and export seams (STORY-OPS-001 section 9) for the later presentation story.
 * Authorization is resolved first; only then is any collaborator (database size, history files, aggregate
 * snapshot) touched, so a denial reveals nothing. No HTTP types: the caller passes the presented session id
 * and asserted access context and maps {@link Denial} to its transport.
 */
public final class OwnerOperationsService {
    public enum Denial { UNAUTHENTICATED, FORBIDDEN }

    /** Either the privileged payload or a content-free denial; {@code cacheControl} is always no-store. */
    public record Result<T>(T value, Denial denial) {
        public static final String CACHE_CONTROL = "no-store";

        public boolean authorized() { return denial == null; }

        public String cacheControl() { return CACHE_CONTROL; }
    }

    public record Summary(Instant generatedAt, ForegroundAccounts foregroundAccounts, DatabaseSize databaseSize,
                          boolean historyAvailable, boolean retentionTruncated, int minuteBuckets, int hourBuckets,
                          MinuteBucket latestMinute, HourBucket latestHour,
                          List<MinuteBucket> minutes, List<HourBucket> hours) {}

    /** Thrown only after authorization, for a malformed or oversized export request. */
    public static final class InvalidExport extends RuntimeException {
        public InvalidExport(String message) { super(message); }
    }

    public static final int MAX_MINUTE_BUCKETS = 360;
    public static final int MAX_HOUR_BUCKETS = 720;
    public static final Duration SUMMARY_MINUTE_WINDOW = Duration.ofHours(6);
    public static final Duration SUMMARY_HOUR_WINDOW = Duration.ofDays(7);
    public static final Duration MAX_EXPORT_RANGE = Duration.ofDays(30);

    /** Bounded: at most 360 minute and 720 hour buckets, each with at most eight route groups. */
    public record Export(Instant generatedAt, boolean historyAvailable, boolean retentionTruncated,
                         List<MinuteBucket> minutes, List<HourBucket> hours) {}

    private final AccountAccessService access;
    private final OwnerPolicy owner;
    private final OperationalHistoryService history;
    private final DatabaseSizeService databaseSize;
    private final Clock clock;

    public OwnerOperationsService(AccountAccessService access, OwnerPolicy owner, OperationalHistoryService history,
                                  DatabaseSizeService databaseSize, Clock clock) {
        this.access = access;
        this.owner = owner;
        this.history = history;
        this.databaseSize = databaseSize;
        this.clock = clock;
    }

    public Result<Summary> summary(String sessionId, String assertedAccessContext) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        HistoryStore.Snapshot snapshot = history.history();
        Instant now = clock.instant();
        // The normal view is filtered by elapsed time, not just by retained row count.
        List<MinuteBucket> minutes = snapshot.minutes().stream()
                .filter(m -> !m.startUtc().isBefore(now.minus(SUMMARY_MINUTE_WINDOW)) && !m.startUtc().isAfter(now))
                .sorted(java.util.Comparator.comparing(MinuteBucket::startUtc)).limit(MAX_MINUTE_BUCKETS).toList();
        List<HourBucket> hours = snapshot.hours().stream()
                .filter(h -> !h.startUtc().isBefore(now.minus(SUMMARY_HOUR_WINDOW)) && !h.startUtc().isAfter(now))
                .sorted(java.util.Comparator.comparing(HourBucket::startUtc)).limit(168).toList();
        return new Result<>(new Summary(now, history.currentActivity(), databaseSize.current(),
                snapshot.available(), snapshot.retentionTruncated(), minutes.size(), hours.size(),
                minutes.isEmpty() ? null : minutes.get(minutes.size() - 1),
                hours.isEmpty() ? null : hours.get(hours.size() - 1), minutes, hours), null);
    }

    public Result<Export> export(String sessionId, String assertedAccessContext) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        HistoryStore.Snapshot snapshot = history.history();
        return new Result<>(new Export(clock.instant(), snapshot.available(), snapshot.retentionTruncated(),
                snapshot.minutes(), snapshot.hours()), null);
    }

    /**
     * Bounded range export. Authorization first; only then is the request validated and history read.
     * Caps count distinct top-level bucket timestamps, never nested route records. Missing buckets are
     * never synthesized.
     *
     * @throws InvalidExport for a malformed, unordered, over-30-day or over-cap request
     */
    public Result<Export> export(String sessionId, String assertedAccessContext, boolean hourly,
                                 Instant from, Instant to) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        if (from == null || to == null || from.isAfter(to)) throw new InvalidExport("from/to must be ordered instants");
        if (Duration.between(from, to).compareTo(MAX_EXPORT_RANGE) > 0) throw new InvalidExport("range exceeds 30 days");
        if (Duration.between(from, to).compareTo(hourly ? Duration.ofHours(MAX_HOUR_BUCKETS)
                : Duration.ofMinutes(MAX_MINUTE_BUCKETS)) > 0) {
            throw new InvalidExport("range exceeds the bucket cap for this resolution");
        }
        HistoryStore.Snapshot snapshot = history.history();
        List<MinuteBucket> minutes = List.of();
        List<HourBucket> hours = List.of();
        if (hourly) {
            hours = snapshot.hours().stream().filter(h -> !h.startUtc().isBefore(from) && !h.startUtc().isAfter(to))
                    .sorted(java.util.Comparator.comparing(HourBucket::startUtc)).toList();
            if (hours.stream().map(HourBucket::startUtc).distinct().count() > MAX_HOUR_BUCKETS) {
                throw new InvalidExport("more than 720 hour buckets requested");
            }
        } else {
            minutes = snapshot.minutes().stream().filter(m -> !m.startUtc().isBefore(from) && !m.startUtc().isAfter(to))
                    .sorted(java.util.Comparator.comparing(MinuteBucket::startUtc)).toList();
            if (minutes.stream().map(MinuteBucket::startUtc).distinct().count() > MAX_MINUTE_BUCKETS) {
                throw new InvalidExport("more than 360 minute buckets requested");
            }
        }
        return new Result<>(new Export(clock.instant(), snapshot.available(), snapshot.retentionTruncated(),
                minutes, hours), null);
    }

    /** Authorization only; touches no history or database collaborator. */
    public Denial authorizeOnly(String sessionId, String assertedAccessContext) {
        return authorize(sessionId, assertedAccessContext);
    }

    /** Owner-only period descriptors; no store is touched before authorization succeeds. */
    public Result<OperationalPeriodStore.Periods> periods(String sessionId, String assertedAccessContext) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        return new Result<>(history.periods(), null);
    }

    /** Owner-only bounded download; the opaque id is resolved only after authorization, inside the store. */
    public Result<OperationalPeriodStore.Download> download(String sessionId, String assertedAccessContext,
                                                            String selectionId) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        return new Result<>(history.openDownload(selectionId), null);
    }

    private Denial authorize(String sessionId, String assertedAccessContext) {
        if (sessionId == null || sessionId.isBlank() || access == null) return Denial.UNAUTHENTICATED;
        AccountSessions.Resolution resolution = access.resolve(sessionId, assertedAccessContext);
        return switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> Denial.UNAUTHENTICATED;
            case CONTEXT_MISMATCH -> Denial.FORBIDDEN;
            case AUTHENTICATED -> owner != null && owner.isOwner(resolution.context()) ? null : Denial.FORBIDDEN;
        };
    }
}
