package application;

import model.AccountScope;
import repo.AccountRegistryRepository;
import repo.AccountSourceStateRepository;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Source-state handling for the keyless account reads (STORY-API-011): Bank, Materials, Luck and the
 * character selector.
 *
 * <p>A read is classified from the account's own publication marker, never from the rows:
 * <ul>
 *   <li>{@link Status#PENDING} - the source was never published for this account. No data is
 *       reported, so a missing source can never read as an empty bank or zero Luck.</li>
 *   <li>{@link Status#CURRENT} - published within the account max age.</li>
 *   <li>{@link Status#STALE} - published but expired. The data is still usable and the read reports
 *       {@link #CREDENTIAL_REQUIRED}: these reads carry no GW2 key, so nothing is admitted and no
 *       configured credential is used.</li>
 * </ul>
 * A successfully published empty source is {@code CURRENT}/{@code STALE} with an empty row list.
 *
 * <p>Marker and rows are captured coherently: the marker is read before and after the rows and the
 * read is repeated (a bounded number of times) if the generation moved in between, so the reported
 * state always describes the rows returned. Authorized use is recorded afterwards, as activity
 * only - never freshness - and not at all when the read failed.
 */
public final class ScopedSourceGate {

    public static final String CREDENTIAL_REQUIRED = "CREDENTIAL_REQUIRED";
    private static final int MAX_ATTEMPTS = 3;

    public enum Status { PENDING, CURRENT, STALE }

    @FunctionalInterface
    public interface StateReader {
        Optional<AccountSourceStateRepository.SourceState> read(AccountScope scope, String source) throws SQLException;
    }

    @FunctionalInterface
    public interface Activity {
        void record(AccountScope scope, Instant at) throws SQLException;
    }

    @FunctionalInterface
    public interface Rows<T> {
        T load() throws SQLException;
    }

    /** @param data null exactly when {@code status} is PENDING */
    public record Read<T>(T data, Status status, Instant completedAt, String refresh) {
        public boolean hasData() {
            return data != null;
        }
    }

    private final StateReader states;
    private final Activity activity;
    private final Clock clock;
    private final Duration maxAge;

    public ScopedSourceGate(StateReader states, Activity activity, Clock clock, Duration maxAge) {
        this.states = states;
        this.activity = activity;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /** The database-backed gate used by the application. */
    public static ScopedSourceGate database(Clock clock, Duration maxAge) {
        return new ScopedSourceGate(
                (scope, source) -> {
                    try (Connection con = Db.open()) {
                        return new AccountSourceStateRepository().read(con, scope, source);
                    }
                },
                (scope, at) -> {
                    try (Connection con = Db.open()) {
                        new AccountRegistryRepository().recordAuthorizedUse(con, scope, at);
                    }
                },
                clock, maxAge);
    }

    /** Reads through {@code gate}, or plainly (no marker consulted) when none is configured. */
    public static <T> Read<T> through(ScopedSourceGate gate, AccountScope scope, String source, Rows<T> rows)
            throws SQLException {
        if (gate != null) return gate.read(scope, source, rows);
        return new Read<>(rows.load(), Status.CURRENT, null, null);
    }

    public <T> Read<T> read(AccountScope scope, String source, Rows<T> rows) throws SQLException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var before = states.read(scope, source);
            if (before.isEmpty()) {
                activity.record(scope, clock.instant());
                return new Read<>(null, Status.PENDING, null, null);
            }
            T data = rows.load();
            var after = states.read(scope, source);
            if (after.isPresent() && after.get().generation() == before.get().generation()) {
                Instant now = clock.instant();
                Instant completedAt = before.get().completedAt();
                boolean stale = completedAt.plus(maxAge).isBefore(now);
                activity.record(scope, now);
                return new Read<>(data, stale ? Status.STALE : Status.CURRENT, completedAt,
                        stale ? CREDENTIAL_REQUIRED : null);
            }
        }
        throw new SQLException("account source changed repeatedly while it was being read; retry");
    }
}
