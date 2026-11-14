package application.ops;

import application.access.AccountAccessService;
import application.access.OwnerPolicy;
import application.access.AccountSessions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Owner-authorized summary and export seams (STORY-OPS-001 section 9) for the later presentation story.
 * Authorization is resolved first; only then is any collaborator (database size, history files, aggregate
 * snapshot) touched, so a denial reveals nothing. No HTTP types: the caller passes the presented session id
 * and asserted access context and maps {@link Denial} to its transport.
 */
public final class OwnerOperationsService {
    public enum Denial { UNAUTHENTICATED, DELETED, FORBIDDEN }

    /** Either the privileged payload or a content-free denial; {@code cacheControl} is always no-store. */
    public record Result<T>(T value, Denial denial) {
        public static final String CACHE_CONTROL = "no-store";

        public boolean authorized() { return denial == null; }

        public String cacheControl() { return CACHE_CONTROL; }
    }

    public record Summary(Instant generatedAt, ForegroundAccounts foregroundAccounts, DatabaseSize databaseSize,
                          boolean historyAvailable, boolean retentionTruncated, int minuteBuckets, int hourBuckets,
                          MinuteBucket latestMinute, HourBucket latestHour) {}

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
        List<MinuteBucket> minutes = snapshot.minutes();
        List<HourBucket> hours = snapshot.hours();
        return new Result<>(new Summary(clock.instant(), history.currentActivity(), databaseSize.current(),
                snapshot.available(), snapshot.retentionTruncated(), minutes.size(), hours.size(),
                minutes.isEmpty() ? null : minutes.get(minutes.size() - 1),
                hours.isEmpty() ? null : hours.get(hours.size() - 1)), null);
    }

    public Result<Export> export(String sessionId, String assertedAccessContext) {
        Denial denial = authorize(sessionId, assertedAccessContext);
        if (denial != null) return new Result<>(null, denial);
        HistoryStore.Snapshot snapshot = history.history();
        return new Result<>(new Export(clock.instant(), snapshot.available(), snapshot.retentionTruncated(),
                snapshot.minutes(), snapshot.hours()), null);
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
            case DELETION_INVALIDATED -> Denial.DELETED;
            case NO_SESSION, EXPIRED -> Denial.UNAUTHENTICATED;
            case CONTEXT_MISMATCH -> Denial.FORBIDDEN;
            case AUTHENTICATED -> owner != null && owner.isOwner(resolution.context()) ? null : Denial.FORBIDDEN;
        };
    }
}
