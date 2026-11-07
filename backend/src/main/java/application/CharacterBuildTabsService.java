package application;

import application.access.AccountContext;
import application.access.AccountOperation;
import model.AccountScope;
import repo.AccountSyncStateRepository;
import repo.CharacterBuildTabsRepository;
import repo.CharacterBuildTabsRepository.Snapshot;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bounded read of one selected character's saved build tabs (STORY-CHAR-007). Stored rows only:
 * it never fetches, starts a refresh or writes. Builds are served only when the parent
 * {@code CHARACTERS} publication exists and the build-tab coverage names that exact parent generation;
 * every other case is a distinct explicit state, never {@code tabs: []}.
 */
public class CharacterBuildTabsService {

    public enum State { AVAILABLE, STALE, PENDING, UNAVAILABLE, GRANT_REQUIRED, ENDPOINT_RESTRICTED, NOT_FOUND }

    /**
     * @param reason stable machine-readable reason; null only for AVAILABLE
     * @param parentCompletedAt/parentGeneration the CHARACTERS publication
     * @param buildsCompletedAt/buildsGeneration the build-tab coverage publication
     * @param coveredParentGeneration the CHARACTERS generation the build-tab coverage describes
     * @param tabs non-null exactly when AVAILABLE or STALE (possibly empty)
     */
    public record Result(State state, String reason, String message, String character,
                         Instant parentCompletedAt, Long parentGeneration,
                         Instant buildsCompletedAt, Long buildsGeneration,
                         Long coveredParentGeneration, List<CharacterBuildTabsRepository.TabView> tabs) {}

    private final CharacterRosterService.RefreshStatus coordinator;
    private final CharacterBuildTabsRepository repository;
    private final CharacterRosterService.ConnectionSource connections;
    private final Clock clock;
    private final Duration maxAge;

    public CharacterBuildTabsService(CharacterRosterService.RefreshStatus coordinator,
                                         CharacterBuildTabsRepository repository, Clock clock, Duration maxAge) {
        this(coordinator, repository, Db::open, clock, maxAge);
    }

    public CharacterBuildTabsService(CharacterRosterService.RefreshStatus coordinator,
                                         CharacterBuildTabsRepository repository,
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
        if (context.lacksGrantFor(AccountOperation.CHARACTER_BUILD_TABS_ENDPOINT)) {
            return denied(State.GRANT_REQUIRED, "BUILD_TABS_GRANT_REQUIRED",
                    "Build tabs need the account, builds and characters permissions.");
        }
        if (context.restrictsEndpointOf(AccountOperation.CHARACTER_BUILD_TABS_READ)) {
            return denied(State.ENDPOINT_RESTRICTED, "ENDPOINT_RESTRICTED",
                    "The verified key is restricted from an endpoint build tabs need.");
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
                    "Characters have not been published for this account yet; build tabs depend on them.");
        }
        long parentGeneration = snapshot.parent().get().generation();
        Instant parentAt = snapshot.parent().get().completedAt();
        if (!snapshot.characterFound()) {
            return new Result(State.NOT_FOUND, "CHARACTER_NOT_FOUND",
                    "No character with that name exists in the current account roster.",
                    null, parentAt, parentGeneration, null, null, null, null);
        }
        if (snapshot.builds().isEmpty() || snapshot.coveredParentGeneration().isEmpty()) {
            boolean refreshing = coordinator != null
                    && coordinator.isRefreshing(scope, AccountSyncStateRepository.CHARACTER_BUILD_TABS);
            boolean failedFirst = !refreshing && coordinator != null
                    && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTER_BUILD_TABS);
            if (failedFirst) {
                // First attempt failed and nothing is retrying: not a perpetual PENDING.
                return new Result(State.UNAVAILABLE, "REFRESH_FAILED",
                        "The build-tab refresh failed and no build tabs are stored yet.",
                        name, parentAt, parentGeneration, null, null, null, null);
            }
            return pending(refreshing ? "REFRESH_IN_PROGRESS" : "COVERAGE_NOT_PUBLISHED",
                    "Build tabs have not been published for this account's characters yet.",
                    name, parentAt, parentGeneration, null, null, null);
        }
        var builds = snapshot.builds().get();
        long covered = snapshot.coveredParentGeneration().get();
        if (covered != parentGeneration) {
            return pending("COVERAGE_INCOMPATIBLE",
                    "Build tabs describe an older character roster; they are pending for the current one.",
                    name, parentAt, parentGeneration, builds.completedAt(), builds.generation(), covered);
        }
        boolean failed = coordinator != null
                && coordinator.lastRefreshFailed(scope, AccountSyncStateRepository.CHARACTER_BUILD_TABS);
        boolean expired = builds.completedAt().plus(maxAge).isBefore(clock.instant());
        State state = failed || expired ? State.STALE : State.AVAILABLE;
        String reason = failed ? "BUILD_TABS_REFRESH_FAILED" : expired ? "BUILD_TABS_SOURCE_EXPIRED" : null;
        String message = failed ? "The latest build-tab refresh failed; showing the last successful data."
                : expired ? "Build-tab data is older than the freshness limit; showing the last successful data."
                : null;
        return new Result(state, reason, message, name, parentAt, parentGeneration, builds.completedAt(),
                builds.generation(), covered, snapshot.tabs());
    }

    private static Result pending(String reason, String message, String name, Instant parentAt, long parentGeneration,
                                  Instant buildsAt, Long buildsGeneration, Long covered) {
        return new Result(State.PENDING, reason, message, name, parentAt, parentGeneration, buildsAt,
                buildsGeneration, covered, null);
    }

    private static Result denied(State state, String reason, String message) {
        return new Result(state, reason, message, null, null, null, null, null, null, null);
    }
}
