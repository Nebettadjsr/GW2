package application;

import application.access.AccountContext;
import application.access.AccountOperation;
import model.AccountScope;
import repo.AccountSyncStateRepository;
import repo.CharacterRepository;
import repo.CharacterRepository.CharacterProfile;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The account-scoped Characters read foundation (STORY-CHAR-001): the compact roster and one selected
 * character's core/crafting facts, with an explicit state for every case in which data cannot be
 * served as a plain success.
 *
 * <p>It reads only stored rows through the shared {@link ScopedSourceGate} (the account's own
 * {@code CHARACTERS} publication marker) and consults the shared refresh coordinator for status. It
 * never fetches upstream data, starts a refresh or writes anything; a missing, restricted or
 * unpublished source is therefore never reported as an empty roster.
 *
 * <p>Admission keeps the combined character source's grants ({@link AccountOperation#CHARACTERS_READ}:
 * {@code account + characters + inventories}), because the stored rows were published together with
 * bags and equipment.
 */
public class CharacterRosterService {

    public enum State { AVAILABLE, PENDING, STALE, GRANT_REQUIRED, ENDPOINT_RESTRICTED, NOT_FOUND, UNAVAILABLE }

    /**
     * @param reason  stable machine-readable reason; null only for AVAILABLE
     * @param message user-facing explanation; null only for AVAILABLE
     * @param roster  non-null exactly when a roster read succeeded (AVAILABLE/STALE), possibly empty
     * @param character the selected character when found (AVAILABLE/STALE of a detail read)
     */
    public record Result(State state, String reason, String message, Instant completedAt, String refresh,
                         List<CharacterProfile> roster, CharacterProfile character) {}

    @FunctionalInterface
    public interface RowReader<T> {
        T read(Connection con, AccountScope scope) throws SQLException;
    }

    private final ScopedSourceGate gate;
    private final RefreshStatus coordinator;
    private final CharacterRepository repository;
    private final ConnectionSource connections;

    /** The shared refresh coordinator's per-source status, as this read needs it. */
    public interface RefreshStatus {
        boolean isRefreshing(AccountScope scope, String source);

        boolean lastRefreshFailed(AccountScope scope, String source);
    }

    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    public CharacterRosterService(ScopedSourceGate gate, RefreshStatus coordinator,
                                  CharacterRepository repository) {
        this(gate, coordinator, repository, Db::open);
    }

    public CharacterRosterService(ScopedSourceGate gate, RefreshStatus coordinator,
                                  CharacterRepository repository, ConnectionSource connections) {
        this.gate = gate;
        this.coordinator = coordinator;
        this.repository = repository;
        this.connections = connections;
    }

    public Result roster(AccountContext context, AccountScope scope) throws SQLException {
        return serve(context, scope, (con, s) -> repository.loadRoster(con, s), false);
    }

    public Result character(AccountContext context, AccountScope scope, String name) throws SQLException {
        return serve(context, scope, (con, s) -> repository.loadCharacter(con, s, name), true);
    }

    @SuppressWarnings("unchecked")
    private <T> Result serve(AccountContext context, AccountScope scope, RowReader<T> reader, boolean detail)
            throws SQLException {
        AccountOperation operation = AccountOperation.CHARACTERS_READ;
        if (context.lacksGrantFor(operation)) {
            return denied(State.GRANT_REQUIRED, "GRANT_REQUIRED",
                    "The verified key lacks a permission needed to read characters: account, characters and inventories.");
        }
        if (context.restrictsEndpointOf(operation)) {
            return denied(State.ENDPOINT_RESTRICTED, "ENDPOINT_RESTRICTED",
                    "The verified key is restricted from the character endpoint, so characters cannot be read.");
        }
        var read = ScopedSourceGate.through(gate, scope, AccountSyncStateRepository.CHARACTERS, () -> {
            // One snapshot for the characters and their disciplines: no mixing of generations.
            try (Connection con = connections.open()) {
                con.setReadOnly(true);
                con.setAutoCommit(false);
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try {
                    return reader.read(con, scope);
                } finally {
                    con.rollback();
                }
            }
        });
        if (!read.hasData()) {
            boolean refreshing = coordinator != null
                    && coordinator.isRefreshing(scope, AccountSyncStateRepository.CHARACTERS);
            return refreshing
                    ? new Result(State.PENDING, "REFRESH_IN_PROGRESS",
                            "Characters are being refreshed; no character data has been published yet.",
                            null, null, null, null)
                    : new Result(State.PENDING, "NOT_YET_PUBLISHED",
                            "Characters have not been published for this account yet.", null, null, null, null);
        }
        boolean failed = coordinator != null
                && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTERS);
        boolean expired = read.status() == ScopedSourceGate.Status.STALE;
        State state = failed || expired ? State.STALE : State.AVAILABLE;
        String reason = failed ? "REFRESH_FAILED" : expired ? "SOURCE_EXPIRED" : null;
        String message = failed ? "The latest character refresh failed; showing the last successful data."
                : expired ? "Character data is older than the freshness limit; showing the last successful data."
                : null;
        if (detail) {
            Optional<CharacterProfile> found = (Optional<CharacterProfile>) read.data();
            if (found.isEmpty()) {
                return new Result(State.NOT_FOUND, "CHARACTER_NOT_FOUND",
                        "No character with that name exists in the current account roster.",
                        read.completedAt(), read.refresh(), null, null);
            }
            return new Result(state, reason, message, read.completedAt(), read.refresh(), null, found.get());
        }
        return new Result(state, reason, message, read.completedAt(), read.refresh(),
                (List<CharacterProfile>) read.data(), null);
    }

    private static Result denied(State state, String reason, String message) {
        return new Result(state, reason, message, null, null, null, null);
    }
}
