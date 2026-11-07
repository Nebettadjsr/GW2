package application;

import application.access.AccountContext;
import application.access.AccountOperation;
import model.AccountScope;
import repo.AccountSyncStateRepository;
import repo.CharacterEquipmentTabsRepository;
import repo.CharacterEquipmentTabsRepository.Snapshot;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bounded read of one selected character's saved equipment tabs (STORY-CHAR-005). Stored rows only:
 * it never fetches, starts a refresh or writes. Equipment is served only when the parent
 * {@code CHARACTERS} publication exists and the equipment coverage names that exact parent generation;
 * every other case is a distinct explicit state, never {@code tabs: []}.
 */
public class CharacterEquipmentTabsService {

    public enum State { AVAILABLE, STALE, PENDING, UNAVAILABLE, GRANT_REQUIRED, ENDPOINT_RESTRICTED, NOT_FOUND }

    /**
     * @param reason stable machine-readable reason; null only for AVAILABLE
     * @param parentCompletedAt/parentGeneration the CHARACTERS publication
     * @param equipmentCompletedAt/equipmentGeneration the equipment coverage publication
     * @param coveredParentGeneration the CHARACTERS generation the equipment coverage describes
     * @param tabs non-null exactly when AVAILABLE or STALE (possibly empty)
     */
    public record Result(State state, String reason, String message, String character,
                         Instant parentCompletedAt, Long parentGeneration,
                         Instant equipmentCompletedAt, Long equipmentGeneration,
                         Long coveredParentGeneration, List<CharacterEquipmentTabsRepository.TabView> tabs) {}

    private final CharacterRosterService.RefreshStatus coordinator;
    private final CharacterEquipmentTabsRepository repository;
    private final CharacterRosterService.ConnectionSource connections;
    private final Clock clock;
    private final Duration maxAge;

    public CharacterEquipmentTabsService(CharacterRosterService.RefreshStatus coordinator,
                                         CharacterEquipmentTabsRepository repository, Clock clock, Duration maxAge) {
        this(coordinator, repository, Db::open, clock, maxAge);
    }

    public CharacterEquipmentTabsService(CharacterRosterService.RefreshStatus coordinator,
                                         CharacterEquipmentTabsRepository repository,
                                         CharacterRosterService.ConnectionSource connections,
                                         Clock clock, Duration maxAge) {
        this.coordinator = coordinator;
        this.repository = repository;
        this.connections = connections;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    public Result read(AccountContext context, AccountScope scope, String name) throws SQLException {
        // Fixed order: the cached-parent dependency (CHARACTERS_READ) first, then the tabs endpoint's own.
        if (context.lacksGrantFor(AccountOperation.CHARACTERS_READ)) {
            return denied(State.GRANT_REQUIRED, "PARENT_GRANT_REQUIRED",
                    "Reading the cached character roster needs the account, characters and inventories permissions.");
        }
        if (context.lacksGrantFor(AccountOperation.CHARACTER_EQUIPMENT_TABS_ENDPOINT)) {
            return denied(State.GRANT_REQUIRED, "EQUIPMENT_GRANT_REQUIRED",
                    "Equipment tabs need the account, builds and characters permissions.");
        }
        if (context.restrictsEndpointOf(AccountOperation.CHARACTER_EQUIPMENT_TABS_READ)) {
            return denied(State.ENDPOINT_RESTRICTED, "ENDPOINT_RESTRICTED",
                    "The verified key is restricted from an endpoint equipment tabs need.");
        }
        Snapshot snapshot;
        try (Connection con = connections.open()) {
            con.setReadOnly(true);
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                snapshot = repository.load(con, scope, name);
            } finally {
                con.rollback();
            }
        }
        if (snapshot.parent().isEmpty()) {
            return denied(State.PENDING, "PARENT_NOT_PUBLISHED",
                    "Characters have not been published for this account yet; equipment tabs depend on them.");
        }
        long parentGeneration = snapshot.parent().get().generation();
        Instant parentAt = snapshot.parent().get().completedAt();
        if (!snapshot.characterFound()) {
            return new Result(State.NOT_FOUND, "CHARACTER_NOT_FOUND",
                    "No character with that name exists in the current account roster.",
                    null, parentAt, parentGeneration, null, null, null, null);
        }
        if (snapshot.equipment().isEmpty() || snapshot.coveredParentGeneration().isEmpty()) {
            boolean refreshing = coordinator != null
                    && coordinator.isRefreshing(scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS);
            boolean failedFirst = !refreshing && coordinator != null
                    && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS);
            if (failedFirst) {
                // First attempt failed and nothing is retrying: not a perpetual PENDING.
                return new Result(State.UNAVAILABLE, "REFRESH_FAILED",
                        "The equipment-tab refresh failed and no equipment tabs are stored yet.",
                        name, parentAt, parentGeneration, null, null, null, null);
            }
            return pending(refreshing ? "REFRESH_IN_PROGRESS" : "COVERAGE_NOT_PUBLISHED",
                    "Equipment tabs have not been published for this account's characters yet.",
                    name, parentAt, parentGeneration, null, null, null);
        }
        var equipment = snapshot.equipment().get();
        long covered = snapshot.coveredParentGeneration().get();
        if (covered != parentGeneration) {
            return pending("COVERAGE_INCOMPATIBLE",
                    "Equipment tabs describe an older character roster; they are pending for the current one.",
                    name, parentAt, parentGeneration, equipment.completedAt(), equipment.generation(), covered);
        }
        boolean failed = coordinator != null
                && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS);
        boolean expired = equipment.completedAt().plus(maxAge).isBefore(clock.instant());
        State state = failed || expired ? State.STALE : State.AVAILABLE;
        String reason = failed ? "EQUIPMENT_REFRESH_FAILED" : expired ? "EQUIPMENT_SOURCE_EXPIRED" : null;
        String message = failed ? "The latest equipment-tab refresh failed; showing the last successful data."
                : expired ? "Equipment-tab data is older than the freshness limit; showing the last successful data."
                : null;
        return new Result(state, reason, message, name, parentAt, parentGeneration, equipment.completedAt(),
                equipment.generation(), covered, snapshot.tabs());
    }

    private static Result pending(String reason, String message, String name, Instant parentAt, long parentGeneration,
                                  Instant equipmentAt, Long equipmentGeneration, Long covered) {
        return new Result(State.PENDING, reason, message, name, parentAt, parentGeneration, equipmentAt,
                equipmentGeneration, covered, null);
    }

    private static Result denied(State state, String reason, String message) {
        return new Result(state, reason, message, null, null, null, null, null, null, null);
    }
}
