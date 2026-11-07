package application;

import application.access.AccountContext;
import application.access.AccountOperation;
import model.AccountScope;
import repo.AccountSyncStateRepository;
import repo.CharacterInventoryLayoutRepository;
import repo.CharacterInventoryLayoutRepository.Bag;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Lazy read of one selected character's bag layout (STORY-CHAR-006) over stored rows only: no upstream
 * call, no refresh, no write. Admission is the combined character source's
 * {@link AccountOperation#CHARACTERS_READ}. A CHARACTERS generation published without a bag manifest is
 * reported as {@code UNAVAILABLE/LAYOUT_REFRESH_REQUIRED}, never as an empty layout.
 */
public class CharacterInventoryLayoutService {

    public enum State { AVAILABLE, STALE, PENDING, UNAVAILABLE, GRANT_REQUIRED, ENDPOINT_RESTRICTED, NOT_FOUND }

    /** What a successfully read layout contains. */
    public enum Contents { HAS_ITEMS, ALL_SLOTS_EMPTY, NO_BAGS }

    /** @param bags non-null exactly when AVAILABLE or STALE; contents likewise */
    public record Result(State state, String reason, String message, String character, Instant completedAt,
                         Long generation, Contents contents, List<Bag> bags) {}

    private final CharacterRosterService.RefreshStatus coordinator;
    private final CharacterInventoryLayoutRepository repository;
    private final CharacterRosterService.ConnectionSource connections;
    private final Clock clock;
    private final Duration maxAge;

    public CharacterInventoryLayoutService(CharacterRosterService.RefreshStatus coordinator,
                                           CharacterInventoryLayoutRepository repository, Clock clock,
                                           Duration maxAge) {
        this(coordinator, repository, Db::open, clock, maxAge);
    }

    public CharacterInventoryLayoutService(CharacterRosterService.RefreshStatus coordinator,
                                           CharacterInventoryLayoutRepository repository,
                                           CharacterRosterService.ConnectionSource connections,
                                           Clock clock, Duration maxAge) {
        this.coordinator = coordinator;
        this.repository = repository;
        this.connections = connections;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    public Result read(AccountContext context, AccountScope scope, String name) throws SQLException {
        if (context.lacksGrantFor(AccountOperation.CHARACTERS_READ)) {
            return plain(State.GRANT_REQUIRED, "GRANT_REQUIRED",
                    "Reading character inventory needs the account, characters and inventories permissions.");
        }
        if (context.restrictsEndpointOf(AccountOperation.CHARACTERS_READ)) {
            return plain(State.ENDPOINT_RESTRICTED, "ENDPOINT_RESTRICTED",
                    "The verified key is restricted from the character endpoint, so inventory cannot be read.");
        }
        CharacterInventoryLayoutRepository.Snapshot snap;
        try (Connection con = connections.open()) {
            con.setReadOnly(true);
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                snap = repository.load(con, scope, name);
            } finally {
                con.rollback();
            }
        }
        boolean refreshing = coordinator != null
                && coordinator.isRefreshing(scope, AccountSyncStateRepository.CHARACTERS);
        boolean failed = coordinator != null
                && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTERS);
        if (snap.parent().isEmpty()) {
            if (refreshing) return plain(State.PENDING, "REFRESH_IN_PROGRESS",
                    "Characters are being refreshed; no inventory has been published yet.");
            if (failed) return plain(State.UNAVAILABLE, "REFRESH_FAILED",
                    "The character refresh failed and no inventory has been published yet.");
            return plain(State.PENDING, "NOT_YET_PUBLISHED", "Characters have not been published for this account yet.");
        }
        Instant at = snap.parent().get().completedAt();
        long generation = snap.parent().get().generation();
        if (!snap.characterFound()) {
            return new Result(State.NOT_FOUND, "CHARACTER_NOT_FOUND",
                    "No character with that name exists in the current account roster.",
                    null, at, generation, null, null);
        }
        if (!snap.manifestPublished()) {
            return new Result(refreshing ? State.PENDING : State.UNAVAILABLE,
                    refreshing ? "REFRESH_IN_PROGRESS" : "LAYOUT_REFRESH_REQUIRED",
                    "The stored characters predate bag layouts; refresh characters to see the inventory layout.",
                    name, at, generation, null, null);
        }
        if (snap.inconsistent()) {
            return new Result(State.UNAVAILABLE, "LAYOUT_REFRESH_REQUIRED",
                    "The stored bag layout is inconsistent; refresh characters to rebuild it.",
                    name, at, generation, null, null);
        }
        boolean anyItem = snap.bags().stream().anyMatch(b -> b.slots().stream().anyMatch(s -> !s.empty()));
        Contents contents = snap.bags().isEmpty() ? Contents.NO_BAGS
                : anyItem ? Contents.HAS_ITEMS : Contents.ALL_SLOTS_EMPTY;
        boolean expired = at.plus(maxAge).isBefore(clock.instant());
        State state = failed || expired ? State.STALE : State.AVAILABLE;
        String reason = failed ? "REFRESH_FAILED" : expired ? "SOURCE_EXPIRED" : null;
        String message = failed ? "The latest character refresh failed; showing the last successful layout."
                : expired ? "Inventory is older than the freshness limit; showing the last successful layout." : null;
        return new Result(state, reason, message, name, at, generation, contents, snap.bags());
    }

    private static Result plain(State state, String reason, String message) {
        return new Result(state, reason, message, null, null, null, null, null);
    }
}
