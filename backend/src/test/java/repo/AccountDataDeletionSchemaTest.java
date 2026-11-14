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
        try (Statement st = reader().createStatement()) {
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
                st.execute("INSERT INTO character_items (account_id, character_id, location, item_id, count) VALUES ('" + id + "', 41, 'INVENTORY', 19721, 3)");
                st.execute("INSERT INTO character_bags (account_id, character_id, bag_index, installed, container_item_id, capacity) VALUES ('" + id + "', 41, 0, true, 8932, 20)");
                st.execute("INSERT INTO character_bag_layout_publication (account_id) VALUES ('" + id + "')");
                st.execute("INSERT INTO character_equipment_coverage (account_id, parent_generation) VALUES ('" + id + "', 1)");
                st.execute("INSERT INTO character_equipment_tabs (account_id, character_id, tab_number, name, is_active) VALUES ('" + id + "', 41, 1, 'Main', true)");
                st.execute("INSERT INTO character_equipment_tab_entries (account_id, character_id, tab_number, entry_order, slot, item_id) VALUES ('" + id + "', 41, 1, 0, 'Helm', 19721)");
                st.execute("INSERT INTO character_build_coverage (account_id, parent_generation) VALUES ('" + id + "', 1)");
                st.execute("INSERT INTO character_build_tabs (account_id, character_id, tab_number, name, is_active, land_utilities, aquatic_utilities) VALUES ('" + id + "', 41, 1, 'Build', true, '{1,2,3}', '{4,5,6}')");
                st.execute("INSERT INTO character_build_tab_specializations (account_id, character_id, tab_number, position, specialization_id, traits) VALUES ('" + id + "', 41, 1, 1, 7, '{1,2,3}')");
            }
            // Shared, account-independent data that deletion must never touch.
            st.execute("INSERT INTO items (item_id, name, type, rarity, level, vendor_value, fetched_at) VALUES (19721, 'Glob of Ectoplasm', 'CraftingMaterial', 'Exotic', 80, 0, now())");
            st.execute("INSERT INTO recipes (recipe_id, type, output_item_id, output_item_count, min_rating, disciplines, fetched_at) VALUES (7319, 'Refinement', 19721, 1, 400, '{Artificer}', now())");
            st.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (7319, 19721, 2)");
            st.execute("INSERT INTO tp_tradeable_items (item_id) VALUES (19721)");
            st.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at) VALUES (19721, 5, 100, 6, 120, now())");
            st.execute("INSERT INTO material_categories (category_id, name, display_order) VALUES (5, 'Cooking', 1)");
            st.execute("INSERT INTO material_category_items (category_id, position, item_id) VALUES (5, 1, 19721)");
            st.execute("INSERT INTO recipe_catalog_publication (complete) VALUES (true)");
            st.execute("INSERT INTO build_specializations (specialization_id, name, elite, minor_traits, major_traits, fetched_at) VALUES (7, 'Spec', false, '{1}', '{2}', now())");
            st.execute("INSERT INTO build_traits (trait_id, name, fetched_at) VALUES (1, 'Trait', now())");
            st.execute("INSERT INTO build_skills (skill_id, name, fetched_at) VALUES (1, 'Skill', now())");
            st.execute("INSERT INTO build_pets (pet_id, name, fetched_at) VALUES (1, 'Pet', now())");
            st.execute("INSERT INTO build_legends (legend_id, swap_skill_id, fetched_at) VALUES ('Legend1', 1, now())");
            st.execute("INSERT INTO equipment_item_details (item_id, details, fetched_at) VALUES (19721, '{}', now())");
            st.execute("INSERT INTO equipment_item_stats (stat_id, attributes, fetched_at) VALUES (1, '{}', now())");
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
        try (Statement st = reader().createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'account_registry'::regclass AND confdeltype <> 'c'""")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "a non-cascading FK would block deleting the account root");
        }
        // Everything reachable from the root by foreign key is account-owned, never shared data.
        try (Statement st = reader().createStatement();
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
        // Every account-owned table reachable from the root must be seeded for both accounts, so a
        // table added later without seeding fails here rather than silently going unchecked.
        for (String table : accountTables()) {
            assertTrue(rows(table, A) > 0, "seed missing for " + table);
            assertEquals(rows(table, A), rows(table, B), "seed differs for " + table);
        }
        assertTrue(accountTables().size() >= 17, "account graph: " + accountTables());
        String sharedBefore = sharedFingerprint();
        String bBefore = ownedFingerprint(B);
        assertTrue(ownedBy(A) > 0);

        deleteA(connect(false));

        assertEquals(0, ownedBy(A));
        assertEquals(bBefore, ownedFingerprint(B));
        assertEquals(sharedBefore, sharedFingerprint());
        // Repeating is harmless and reports nothing.
        deleteA(connect(false));
        assertEquals(bBefore, ownedFingerprint(B));
        assertEquals(sharedBefore, sharedFingerprint());
    }

    @Test
    void injectedMidTransactionFailureRollsBackTheWholeDataset() throws Exception {
        try (Statement st = reader().createStatement()) {
            st.execute("""
                CREATE FUNCTION fail_delete() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$ LANGUAGE plpgsql""");
            st.execute("CREATE TRIGGER boom BEFORE DELETE ON characters FOR EACH ROW EXECUTE FUNCTION fail_delete()");
        }
        String before = ownedFingerprint(A);
        String bBefore = ownedFingerprint(B);
        String sharedBefore = sharedFingerprint();
        Connection con = connect(false);
        assertThrows(SQLException.class, () -> registry.deleteAccount(con, AccountScope.of(A)));
        con.rollback();
        assertEquals(before, ownedFingerprint(A), "the whole A dataset is back after rollback");
        assertEquals(bBefore, ownedFingerprint(B));
        assertEquals(sharedBefore, sharedFingerprint());
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
        try (Statement st = reader().createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM account_registry WHERE account_id = '" + id + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long total(String table) throws Exception {
        try (Statement st = reader().createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Every table reachable from the registry root by foreign key, discovered from the real schema. */
    private List<String> accountTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement st = reader().createStatement();
             ResultSet rs = st.executeQuery("""
                WITH RECURSIVE reach(rel) AS (
                  SELECT 'account_registry'::regclass
                  UNION
                  SELECT c.conrelid FROM pg_constraint c JOIN reach r ON c.confrelid = r.rel WHERE c.contype = 'f')
                SELECT rel::text FROM reach ORDER BY 1""")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    private long rows(String table, UUID id) throws Exception {
        try (Statement st = reader().createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + " WHERE account_id = '" + id + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long ownedBy(UUID id) throws Exception {
        long sum = 0;
        for (String t : accountTables()) sum += rows(t, id);
        return sum;
    }

    /** Row content of every account-owned table for one account, so any change is detected. */
    private String ownedFingerprint(UUID id) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String t : accountTables()) {
            try (Statement st = reader().createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*), coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '') FROM "
                         + t + " x WHERE account_id = '" + id + "'")) {
                rs.next();
                out.append(t).append('=').append(rs.getLong(1)).append(':').append(rs.getString(2)).append(';');
            }
        }
        return out.toString();
    }

    private static final List<String> SHARED = List.of("items", "recipes", "recipe_ingredients", "tp_tradeable_items",
            "tp_prices", "material_categories", "material_category_items", "recipe_catalog_publication",
            "build_specializations", "build_traits", "build_skills", "build_pets", "build_legends",
            "equipment_item_details", "equipment_item_stats");

    private String sharedFingerprint() throws Exception {
        StringBuilder out = new StringBuilder();
        for (String t : SHARED) {
            try (Statement st = reader().createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*), coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '') FROM " + t + " x")) {
                rs.next();
                assertTrue(rs.getLong(1) > 0, "shared table not seeded: " + t);
                out.append(t).append('=').append(rs.getLong(1)).append(':').append(rs.getString(2)).append(';');
            }
        }
        return out.toString();
    }

    private Connection reader;

    private Connection reader() throws SQLException {
        if (reader == null) reader = connect(true);
        return reader;
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
