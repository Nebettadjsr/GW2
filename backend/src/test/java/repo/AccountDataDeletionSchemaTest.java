package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-DELETE-001 against the production Flyway chain in an isolated schema. */
class AccountDataDeletionSchemaTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final String schema = "test_account_delete_" + UUID.randomUUID().toString().replace("-", "");
    private final List<Connection> open = new ArrayList<>();
    private final AccountRegistryRepository registry = new AccountRegistryRepository();

    @BeforeEach
    void setUp() throws Exception {
        try (Connection admin = connect(false); Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        try (Statement st = connect(true).createStatement()) {
            st.execute("INSERT INTO account_registry (account_id) VALUES ('" + A + "'), ('" + B + "')");
            for (UUID id : new UUID[] {A, B}) {
                st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + id + "', 1, 19721, 2)");
                st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('" + id + "', 19721, 5)");
                st.execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + id + "', 7319)");
                st.execute("INSERT INTO account_luck (account_id, consumed_luck) VALUES ('" + id + "', 10)");
                st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('" + id + "', 41, 'Same')");
                st.execute("INSERT INTO character_crafting (account_id, character_id, discipline, rating) VALUES ('" + id + "', 41, 'Artificer', 400)");
                st.execute("INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES ('" + id + "', 41, 7319)");
                st.execute("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('" + id + "', 'BANK', 1, now())");
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection c : open) try { if (!c.isClosed()) { c.rollback(); c.close(); } } catch (SQLException ignored) { }
        try (Connection admin = connect(false); Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void everyForeignKeyToTheRootCascadesAndTheGraphStaysInsideAccountTables() throws Exception {
        try (Statement st = connect(true).createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'account_registry'::regclass AND confdeltype <> 'c'""")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "a non-cascading FK would block deleting the account root");
        }
        // Everything reachable from the root by foreign key is account-owned, never shared data.
        try (Statement st = connect(true).createStatement();
             ResultSet rs = st.executeQuery("""
                WITH RECURSIVE reach(rel) AS (
                  SELECT 'account_registry'::regclass
                  UNION
                  SELECT c.conrelid FROM pg_constraint c JOIN reach r ON c.confrelid = r.rel WHERE c.contype = 'f')
                SELECT rel::text FROM reach""")) {
            while (rs.next()) {
                String table = rs.getString(1);
                assertTrue(table.startsWith("account_") || table.startsWith("character"),
                        "cascade graph reaches a non-account table: " + table);
            }
        }
    }

    @Test
    void deletingAWipesAEverywhereAndLeavesBAndSharedDataExactlyAsTheyWere() throws Exception {
        long sharedBefore = total("items") + total("recipes") + total("recipe_catalog_publication");
        long bBefore = ownedBy(B);
        assertTrue(ownedBy(A) > 0);

        deleteA(connect(false));

        assertEquals(0, ownedBy(A));
        assertEquals(bBefore, ownedBy(B));
        assertEquals(sharedBefore, total("items") + total("recipes") + total("recipe_catalog_publication"));
        // Repeating is harmless and reports nothing.
        deleteA(connect(false));
        assertEquals(bBefore, ownedBy(B));
    }

    @Test
    void injectedMidTransactionFailureRollsBackTheWholeDataset() throws Exception {
        try (Statement st = connect(true).createStatement()) {
            st.execute("""
                CREATE FUNCTION fail_delete() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$ LANGUAGE plpgsql""");
            st.execute("CREATE TRIGGER boom BEFORE DELETE ON characters FOR EACH ROW EXECUTE FUNCTION fail_delete()");
        }
        long before = ownedBy(A);
        Connection con = connect(false);
        assertThrows(SQLException.class, () -> registry.deleteAccount(con, AccountScope.of(A)));
        con.rollback();
        assertEquals(before, ownedBy(A));
    }

    @Test
    void authorizedUseAndDeletionSerializeOnTheWriterLockInBothOrders() throws Exception {
        // 1. An old authorized-use transaction holds the lock first: deletion waits, then removes the root.
        Connection use = connect(false);
        registry.recordAuthorizedUse(use, AccountScope.of(A), Instant.now());
        CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> {
            try { deleteA(connect(false)); } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThrows(java.util.concurrent.TimeoutException.class, () -> deletion.get(300, TimeUnit.MILLISECONDS));
        use.commit();
        deletion.get(5, TimeUnit.SECONDS);
        assertEquals(0, roots(A));

        // 2. Deletion holds the lock first: a production-path (autocommit) activity write waits for
        // the commit and cannot slip in between the lock and the delete.
        Connection del = connect(false);
        registry.deleteAccount(del, AccountScope.of(B));
        CompletableFuture<Void> late = CompletableFuture.runAsync(() -> {
            try {
                Connection c = connect(true);
                registry.recordAuthorizedUse(c, AccountScope.of(B), Instant.now());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThrows(java.util.concurrent.TimeoutException.class, () -> late.get(300, TimeUnit.MILLISECONDS));
        assertEquals(1, roots(B), "uncommitted deletion is invisible and the writer is queued behind it");
        del.commit();
        late.get(5, TimeUnit.SECONDS);
        // The request-lifetime fence (gate) is what keeps such an old request from existing at all;
        // here we only show the DB ordering. A legitimate reconnect can recreate the root:
        assertEquals(1, roots(B));
        Connection again = connect(true);
        assertTrue(registry.recordAuthorizedUse(again, AccountScope.of(A), Instant.now()));
        assertEquals(1, roots(A));
        assertTrue(again.getAutoCommit(), "autocommit is restored");
    }

    private void deleteA(Connection con) throws Exception {
        try {
            registry.deleteAccount(con, AccountScope.of(A));
            con.commit();
        } finally {
            con.close();
        }
    }

    private long roots(UUID id) throws Exception {
        try (Statement st = connect(true).createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM account_registry WHERE account_id = '" + id + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long total(String table) throws Exception {
        try (Statement st = connect(true).createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long ownedBy(UUID id) throws Exception {
        long sum = 0;
        for (String t : List.of("account_registry", "account_source_state", "account_bank", "account_materials",
                "account_recipes", "account_luck", "characters", "character_crafting", "character_recipes")) {
            try (Statement st = connect(true).createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM " + t + " WHERE account_id = '" + id + "'")) {
                rs.next();
                sum += rs.getLong(1);
            }
        }
        return sum;
    }

    private Connection connect(boolean autoCommit) throws SQLException {
        Connection c = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        open.add(c);
        try (Statement st = c.createStatement()) {
            if (schema != null) st.execute("SET search_path TO " + schema);
        } catch (SQLException e) {
            // schema not created yet (first admin connection)
        }
        c.setAutoCommit(autoCommit);
        return c;
    }
}
