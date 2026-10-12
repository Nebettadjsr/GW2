package web;

import application.AccountRefreshCoordinator;
import application.AccountRefreshService;
import application.BackgroundTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import repo.AccountSyncStateRepository;
import repo.Db;
import repo.EnvConfig;
import sync.AccountRefreshGateway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criterion 7, interception half: the framework freshness gate was changed
 * only as far as Crafting Profit's cached-result contract requires, and every other feature keeps
 * exactly the policy it had.
 *
 * <p>Two claims, both of which the story could have broken silently:
 *
 * <ul>
 *   <li>The two Profit routes are no longer gated here at all, so an expired marker cannot refuse
 *       them (TARGET_ARCHITECTURE.md §9.1: the feature answers from its published inputs and
 *       reports their freshness itself).</li>
 *   <li>Bank, material storage, Luck, Discovery, Discovery resolution and selector options keep
 *       their own required source sets and still raise {@code ACCOUNT_DATA_STALE} naming exactly
 *       their own stale sources. Changing admission to the coordinator must not have changed which
 *       routes are gated or what they answer.</li>
 * </ul>
 *
 * <p>The last test is the interceptor-level form of {@code docs/bugs/BUG-2026-001}: a narrow
 * selector-options sync followed by a four-source Discovery request must end with all four sources
 * actually fetched by that single writer, not with three of them silently skipped.
 *
 * <p>Runs against a real local PostgreSQL in a disposable, uniquely named schema that
 * {@link Db#TEST_SCHEMA_PROPERTY} points every {@code Db.open()} at for the duration of the test,
 * so the developer's own database is never read or written (same pattern as
 * {@code repo.BankRepositoryTest}). No GW2 API call is made: the refresh gateway is a recorder.
 */
class AccountDataFreshnessInterceptorTest {

    private static final Duration MAX_AGE = Duration.ofMinutes(15);

    private static final Set<String> CRAFTING_SOURCES = Set.of(
            AccountSyncStateRepository.BANK,
            AccountSyncStateRepository.MATERIALS,
            AccountSyncStateRepository.RECIPES,
            AccountSyncStateRepository.CHARACTERS);

    private final String schema = "test_freshness_gate_" + UUID.randomUUID().toString().replace("-", "");

    private final RecordingGateway gateway = new RecordingGateway();
    private final FakeTasks tasks = new FakeTasks();
    private final AccountRefreshCoordinator coordinator =
            new AccountRefreshCoordinator(new AccountRefreshService(gateway), tasks);
    private final AccountDataFreshnessInterceptor interceptor =
            new AccountDataFreshnessInterceptor(coordinator, MAX_AGE);

    private Connection admin;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        admin = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        AccountSyncStateRepository.ensure(admin);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void theProfitRoutesAreNotGatedHereEvenWithNoSourceEverSynchronized() throws Exception {
        // Nothing is marked at all, which is the harshest input the old gate had: it refused with
        // ACCOUNT_DATA_STALE naming all four sources.
        assertTrue(preHandle("/api/crafting/profit"),
                "the Profit table route must reach its controller, which reports freshness itself");
        assertTrue(preHandle("/api/crafting/profit/resolution"),
                "the Profit detail route must reach its controller too");

        assertEquals(List.of(), gateway.calls, "the gate must not admit refresh for an ungated route");
        assertEquals(List.of(), tasks.operations, "and must admit no task at all");
    }

    @Test
    void everyOtherGatedRouteKeepsItsOwnRequiredSourcesAndTheStaleResponse() {
        assertStaleFor("/api/account/bank", Set.of(AccountSyncStateRepository.BANK));
        assertStaleFor("/api/account/materials", Set.of(AccountSyncStateRepository.MATERIALS));
        assertStaleFor("/api/account/luck", Set.of(AccountSyncStateRepository.LUCK));
        assertStaleFor("/api/crafting/discovery", CRAFTING_SOURCES);
        assertStaleFor("/api/crafting/discovery/resolution", CRAFTING_SOURCES);
        assertStaleFor("/api/crafting/selector-options", Set.of(AccountSyncStateRepository.CHARACTERS));
    }

    @Test
    void anUngatedPathIsLetThroughWithoutReadingAnyMarker() throws Exception {
        assertTrue(preHandle("/api/system/status"));
        assertTrue(preHandle("/api/crafting/ingredient-search"));
        assertEquals(List.of(), tasks.operations);
    }

    @Test
    void freshMarkersLetAGatedRouteThroughWithoutAdmittingWork() throws Exception {
        markAll(Instant.now().minus(Duration.ofMinutes(1)));

        assertTrue(preHandle("/api/crafting/discovery"),
                "every required source is inside the TTL, so the gate must not refuse");
        assertEquals(List.of(), tasks.operations, "a fresh request must admit no refresh");
    }

    @Test
    void aGatedRouteNamesOnlyItsOwnStaleSources() {
        markAll(Instant.now().minus(Duration.ofMinutes(1)));
        mark(AccountSyncStateRepository.MATERIALS, Instant.now().minus(Duration.ofMinutes(20)));

        AccountDataStaleException stale = assertThrows(AccountDataStaleException.class,
                () -> preHandle("/api/crafting/discovery"));

        assertEquals(Set.of(AccountSyncStateRepository.MATERIALS), stale.sources(),
                "a source inside the TTL must not be reported stale or refreshed");
        assertEquals(Set.of(AccountSyncStateRepository.MATERIALS), coordinator.outstandingSources());
    }

    @Test
    void theNarrowSelectorOptionsSyncEndsUpRefreshingTheFourSourcesDiscoveryDemanded() {
        markAll(Instant.now().minus(Duration.ofMinutes(20)));

        // 1. Selector options: CHARACTERS only. One ACCOUNT_SYNC is admitted and not yet run.
        AccountDataStaleException narrow = assertThrows(AccountDataStaleException.class,
                () -> preHandle("/api/crafting/selector-options"));
        assertEquals(Set.of(AccountSyncStateRepository.CHARACTERS), narrow.sources());
        assertEquals(List.of(AccountRefreshCoordinator.OPERATION), tasks.operations);

        // 2. Discovery, while that task is unfinished: it needs all four sources. Before this story
        // it was handed the CHARACTERS-only task and told to wait for it.
        AccountDataStaleException broad = assertThrows(AccountDataStaleException.class,
                () -> preHandle("/api/crafting/discovery"));
        assertEquals(CRAFTING_SOURCES, broad.sources());
        assertEquals(narrow.taskId(), broad.taskId(), "the single account writer keeps its identity");
        assertEquals(1, tasks.operations.size(), "at most one unfinished ACCOUNT_SYNC");
        assertEquals(CRAFTING_SOURCES, coordinator.outstandingSources(),
                "the second caller's sources must survive, because nothing has refreshed them");

        // 3. That one writer runs, and refreshes the accumulated demand rather than its first batch.
        tasks.run(narrow.taskId());

        assertEquals(CRAFTING_SOURCES, Set.copyOf(gateway.calls),
                "all four demanded sources must actually be fetched by that single run");
        assertEquals(4, gateway.calls.size(), "and each exactly once");
        assertEquals(Set.of(), coordinator.outstandingSources(), "nothing may be left outstanding");
        assertEquals(1, tasks.operations.size(), "still exactly one ACCOUNT_SYNC was ever admitted");
    }

    // ---------- helpers ----------

    private void assertStaleFor(String path, Set<String> expected) {
        AccountDataStaleException stale = assertThrows(AccountDataStaleException.class,
                () -> preHandle(path), path + " must keep its ACCOUNT_DATA_STALE policy");
        assertEquals(expected, stale.sources(), path + ": required sources");
        assertEquals(AccountRefreshCoordinator.OPERATION, tasks.operationOf(stale.taskId()),
                path + ": the advertised task must be the account sync");
    }

    private boolean preHandle(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private void markAll(Instant at) {
        for (String source : List.of(AccountSyncStateRepository.BANK,
                AccountSyncStateRepository.MATERIALS,
                AccountSyncStateRepository.RECIPES,
                AccountSyncStateRepository.LUCK,
                AccountSyncStateRepository.CHARACTERS)) {
            mark(source, at);
        }
    }

    private void mark(String source, Instant at) {
        try {
            AccountSyncStateRepository.mark(admin, source, at);
        } catch (Exception failure) {
            throw new IllegalStateException("could not seed " + source, failure);
        }
    }

    /** Records which sources were fetched; never reaches the GW2 API. */
    private static final class RecordingGateway extends AccountRefreshGateway {
        final List<String> calls = new ArrayList<>();

        @Override public void syncAccountBank() { calls.add(AccountSyncStateRepository.BANK); }
        @Override public void syncAccountMaterials() { calls.add(AccountSyncStateRepository.MATERIALS); }
        @Override public void syncAccountRecipes() { calls.add(AccountSyncStateRepository.RECIPES); }
        @Override public void syncAccountLuck() { calls.add(AccountSyncStateRepository.LUCK); }
        @Override public void syncCharacterCraftingAndRecipes() { calls.add(AccountSyncStateRepository.CHARACTERS); }
    }

    /**
     * The task facility's two relied-upon guarantees, with the body run only when a test says so,
     * so the narrow-then-broad interleaving above is driven rather than raced.
     */
    private static final class FakeTasks implements BackgroundTasks {
        final List<String> operations = new ArrayList<>();
        private final Map<String, String> operationById = new LinkedHashMap<>();
        private final Map<String, Work> workById = new LinkedHashMap<>();
        private final Map<String, String> stateById = new LinkedHashMap<>();

        @Override
        public synchronized String submit(String operation, Work work) throws OperationAlreadyRunning {
            for (Map.Entry<String, String> task : operationById.entrySet()) {
                if (task.getValue().equals(operation) && "RUNNING".equals(stateById.get(task.getKey()))) {
                    throw new OperationAlreadyRunning(operation, task.getKey());
                }
            }
            String id = operation + "#" + (operationById.size() + 1);
            operations.add(operation);
            operationById.put(id, operation);
            workById.put(id, work);
            stateById.put(id, "RUNNING");
            return id;
        }

        @Override
        public synchronized Optional<Status> find(String taskId) {
            String state = stateById.get(taskId);
            if (state == null) return Optional.empty();
            boolean failed = "FAILED".equals(state);
            return Optional.of(new Status(taskId, operationById.get(taskId), state,
                    failed || "SUCCEEDED".equals(state), failed, null, null));
        }

        synchronized String operationOf(String taskId) {
            return operationById.get(taskId);
        }

        void run(String taskId) {
            Work work;
            synchronized (this) {
                work = workById.get(taskId);
            }
            try {
                work.run();
                synchronized (this) {
                    stateById.put(taskId, "SUCCEEDED");
                }
            } catch (Exception failure) {
                synchronized (this) {
                    stateById.put(taskId, "FAILED");
                }
            }
        }
    }
}
