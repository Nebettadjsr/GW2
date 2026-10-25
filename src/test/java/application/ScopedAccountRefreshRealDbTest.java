package application;

import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import repo.BankRepository;
import repo.Db;
import repo.EnvConfig;
import repo.RecipeRepository;
import sync.AccountRefreshGateway;
import web.BackgroundTaskServiceTasks;
import web.task.BackgroundTaskService;
import web.task.TaskState;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STORY-SYNC-006 required real-PostgreSQL check, through the REAL wiring: scoped coordinator ->
 * {@link AccountRefreshService} -> {@link AccountRefreshGateway} -> {@code AccountSync} fetch,
 * parse and atomic publication, on the real task facility and the production Flyway chain in a
 * disposable, uniquely named schema (never the public schema). Only the upstream is replaced, by an
 * offline per-key fixture fetcher. Runs in the default suite like the other schema-isolated tests.
 */
class ScopedAccountRefreshRealDbTest {

    private static final String A = "10000000-0000-0000-0000-00000000000a";
    private static final String B = "20000000-0000-0000-0000-00000000000b";
    private static final Set<AccountGrant> GRANTS = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.UNLOCKS);
    private static final String BANK = AccountSyncStateRepository.BANK;
    private static final String MATERIALS = AccountSyncStateRepository.MATERIALS;
    private static final String RECIPES = AccountSyncStateRepository.RECIPES;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema = "test_scoped_refresh_" + UUID.randomUUID().toString().replace("-", "");
    private final BackgroundTaskService service = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), 8);
    private final Map<String, AccountProof> proofs = new ConcurrentHashMap<>();
    /** key -> (url suffix after /v2/ -> response JSON text). */
    private final Map<String, Map<String, String>> upstream = new ConcurrentHashMap<>();
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            new AccountRefreshService(new AccountRefreshGateway()), new BackgroundTaskServiceTasks(service),
            new AccountAccessService(key -> {
                AccountProof proof = proofs.get(key);
                if (proof == null) throw new InvalidCredentialException("rejected");
                return proof;
            }, sessions),
            key -> url -> {
                String body = upstream.get(key).get(url.substring(url.indexOf("/v2/") + 4));
                if (body == null) throw new IllegalStateException("no fixture for " + url);
                return JSON.readTree(body);
            });

    private Connection admin;
    private String previousSchemaProperty;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        previousSchemaProperty = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
        if (previousSchemaProperty == null) System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        else System.setProperty(Db.TEST_SCHEMA_PROPERTY, previousSchemaProperty);
        if (admin == null) return;
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    private AccountSessions.Issued login(String account, String key, String bank, String materials,
                                         String recipes) {
        AccountProof proof = new AccountProof(account, GRANTS, Set.of(), null);
        proofs.put(key, proof);
        upstream.put(key, Map.of(
                "account", "{\"id\":\"" + account + "\"}",
                "account/bank", bank,
                "account/materials", materials,
                "account/recipes", recipes));
        return sessions.establish(proof);
    }

    private TaskState run(AccountSessions.Issued s, String key, String... sources) throws Exception {
        var admission = coordinator.require(s.context(), s.sessionId(), key, Set.of(sources));
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_ADMITTED, admission.reuse());
        for (int i = 0; i < 1000; i++) {
            var task = service.find(admission.taskId()).orElseThrow();
            if (task.state().isTerminal()) return task.state();
            Thread.sleep(10);
        }
        return fail("task did not finish");
    }

    @Test
    void twoAccountsWithOverlappingIdentifiersSynchronizeWithoutCrossAccountChanges() throws Exception {
        var a = login(A, "key-a", "[{\"id\":100,\"count\":2}]",
                "[{\"id\":100,\"category\":5,\"count\":11}]", "[7319]");
        var b = login(B, "key-b", "[{\"id\":100,\"count\":7}]",
                "[{\"id\":100,\"category\":5,\"count\":19}]", "[7319]");

        assertEquals(TaskState.SUCCEEDED, run(a, "key-a", BANK, MATERIALS, RECIPES));
        assertEquals(TaskState.SUCCEEDED, run(b, "key-b", BANK, MATERIALS, RECIPES));

        assertEquals(2, bankCount(A));
        assertEquals(7, bankCount(B));
        assertEquals(11, materialCount(A));
        assertEquals(19, materialCount(B));
        assertEquals(Set.of(7319), new RecipeRepository().loadKnownRecipeIds(admin, AccountScope.of(A)));
        assertEquals(Set.of(7319), new RecipeRepository().loadKnownRecipeIds(admin, AccountScope.of(B)));
        assertEquals(1, generation(A, BANK));
        assertEquals(1, generation(B, BANK));
    }

    @Test
    void aReplacementKeyForTheSameGuidReusesTheSamePersistedDataset() throws Exception {
        var first = login(A, "key-a-1", "[{\"id\":100,\"count\":2}]",
                "[{\"id\":100,\"category\":5,\"count\":11}]", "[7319]");
        var other = login(B, "key-b", "[{\"id\":100,\"count\":7}]",
                "[{\"id\":100,\"category\":5,\"count\":19}]", "[7319]");
        assertEquals(TaskState.SUCCEEDED, run(first, "key-a-1", BANK));
        assertEquals(TaskState.SUCCEEDED, run(other, "key-b", BANK));

        var replacement = login(A, "key-a-2", "[{\"id\":100,\"count\":5}]",
                "[{\"id\":100,\"category\":5,\"count\":11}]", "[7319]");
        assertEquals(TaskState.SUCCEEDED, run(replacement, "key-a-2", BANK));

        assertEquals(1, count("account_bank", A), "the replacement key updates the one dataset, not a copy");
        assertEquals(5, bankCount(A));
        assertEquals(2, generation(A, BANK));
        assertEquals(1, count("account_registry", A));
        assertEquals(7, bankCount(B));
        assertEquals(1, generation(B, BANK));
    }

    @Test
    void aFailedSourceKeepsItsLastGoodRowsAndGenerationWhileAnIndependentSourceCommits() throws Exception {
        var good = login(A, "key-a-good", "[{\"id\":100,\"count\":2}]",
                "[{\"id\":100,\"category\":5,\"count\":11}]", "[7319]");
        assertEquals(TaskState.SUCCEEDED, run(good, "key-a-good", BANK, MATERIALS));

        // Same account, new key: bank now answers with a malformed payload, materials with new data.
        var bad = login(A, "key-a-bad", "{\"text\":\"upstream-secret-payload\"}",
                "[{\"id\":100,\"category\":5,\"count\":12}]", "[7319]");
        var admission = coordinator.require(bad.context(), bad.sessionId(), "key-a-bad",
                Set.of(BANK, MATERIALS));
        TaskState state = null;
        for (int i = 0; i < 1000 && (state == null || !state.isTerminal()); i++) {
            state = service.find(admission.taskId()).orElseThrow().state();
            Thread.sleep(10);
        }

        assertEquals(TaskState.FAILED, state);
        assertEquals(2, bankCount(A), "last-good bank rows stay");
        assertEquals(1, generation(A, BANK), "the failed source keeps its generation");
        assertEquals(12, materialCount(A), "the independent source committed");
        assertEquals(2, generation(A, MATERIALS));
        var failure = service.find(admission.taskId()).orElseThrow().failure();
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains("upstream-secret-payload"));
        assertFalse(coordinator.isActive(AccountScope.of(A)));
    }

    // ---------- reads ----------

    private int bankCount(String account) throws SQLException {
        return new BankRepository().loadBankSlots(admin, AccountScope.of(account)).get(0).count();
    }

    private int materialCount(String account) throws SQLException {
        try (var ps = admin.prepareStatement("SELECT count FROM account_materials WHERE account_id = ? AND item_id = 100")) {
            ps.setObject(1, UUID.fromString(account));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private long generation(String account, String source) throws SQLException {
        return new AccountSourceStateRepository().read(admin, AccountScope.of(account), source)
                .orElseThrow().generation();
    }

    private int count(String table, String account) throws SQLException {
        return count(table, account, "account_id");
    }

    private int count(String table, String account, String column) throws SQLException {
        try (var ps = admin.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, UUID.fromString(account));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }
}
