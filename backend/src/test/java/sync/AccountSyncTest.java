package sync;

import model.BankSlot;
import model.MaterialStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for {@code AccountSync}'s
 * three database-write flows: {@code upsertAccountBank} ({@code account_bank}),
 * {@code upsertAccountMaterials} ({@code account_materials}), and {@code upsertAccountRecipes}
 * ({@code account_recipes}). These have only ever run against the developer's real database via
 * the live application; this is their first automated coverage.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@code CharacterSyncTest} (STORY-TEST-003) and {@code TpSyncTest}
 * (STORY-TEST-006). Seeded rows are already-constructed model records
 * ({@code BankSlot}/{@code MaterialStack}/plain recipe IDs) - no live GW2 API call is made, and
 * {@code syncAccountBank}/{@code syncAccountMaterials}/{@code syncAccountRecipes}'s fetch paths
 * are never exercised.
 */
class AccountSyncTest {

    private final String schema = "test_accountsync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE account_bank (
                    slot        INTEGER PRIMARY KEY,
                    item_id     INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    bound_to    TEXT,
                    charges     INTEGER,
                    stats_id    INTEGER,
                    stats_attrs JSONB,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE account_materials (
                    item_id     INTEGER PRIMARY KEY,
                    category    INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("CREATE TABLE account_materials_sync (id INTEGER PRIMARY KEY, fetched_at TIMESTAMPTZ NOT NULL)");
            st.execute("""
                CREATE TABLE account_recipes (
                    recipe_id   INTEGER PRIMARY KEY,
                    fetched_at  TIMESTAMPTZ
                )
                """);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    // Postgres timestamptz has microsecond precision; truncate so round-tripped values compare equal.
    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void upsertAccountBank_insertsUpdatesOnConflictAndDeletesStaleRows() throws Exception {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        // Initial sync run: a bound item in slot 0 (NULL binding case in slot 1), plus a slot
        // that will not be refreshed on the second run.
        BankSlot boundSlot = new BankSlot(0, 111, 5, "Soulbound", "Some Hero", 3, 42, "{\"a\":1}");
        BankSlot unboundSlot = new BankSlot(1, 222, 9, null, null, null, null, null);
        BankSlot droppedSlot = new BankSlot(2, 333, 1, null, null, null, null, null);
        AccountSync.upsertAccountBank(con, List.of(boundSlot, unboundSlot, droppedSlot), ts(t1));

        // (a) Initial insert landed correctly, including binding/bound_to for the bound slot and
        // NULL binding/bound_to preserved for the unbound slot.
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT item_id, count, binding, bound_to, charges, stats_id, " +
                             "(stats_attrs = '{\"a\":1}'::jsonb) AS attrs_match " +
                             "FROM account_bank WHERE slot = 0")) {
            assertTrue(rs.next());
            assertEquals(111, rs.getInt("item_id"));
            assertEquals(5, rs.getInt("count"));
            assertEquals("Soulbound", rs.getString("binding"));
            assertEquals("Some Hero", rs.getString("bound_to"));
            assertEquals(3, rs.getInt("charges"));
            assertEquals(42, rs.getInt("stats_id"));
            assertTrue(rs.getBoolean("attrs_match"));
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT item_id, binding, bound_to FROM account_bank WHERE slot = 1")) {
            assertTrue(rs.next());
            assertEquals(222, rs.getInt("item_id"));
            assertNull(rs.getString("binding"));
            assertNull(rs.getString("bound_to"));
        }

        // Second sync run: slot 0's binding is cleared (NULL) via DbBind.setStringOrNull, count
        // in slot 1 changes (an overlapping key), and slot 2's item is no longer reported by this
        // run - i.e. absent from the seeded rows - so it must be cleaned up as stale.
        BankSlot boundSlotNowUnbound = new BankSlot(0, 111, 5, null, null, null, null, null);
        BankSlot unboundSlotUpdated = new BankSlot(1, 222, 20, null, null, null, null, null);
        AccountSync.upsertAccountBank(con, List.of(boundSlotNowUnbound, unboundSlotUpdated), ts(t2));

        // (b) Overlapping keys updated the existing rows rather than duplicating them, and the
        // NULL binding is written and preserved correctly.
        assertEquals(1, rowCountForSlot(0));
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT binding, bound_to FROM account_bank WHERE slot = 0")) {
            assertTrue(rs.next());
            assertNull(rs.getString("binding"));
            assertNull(rs.getString("bound_to"));
        }
        assertEquals(20, slotCount(1));

        // (c) Slot 2's row (fetched_at = t1, predating this run's t2) was deleted by the
        // stale-row cleanup; slots 0 and 1 (fetched_at = t2) survive.
        assertEquals(0, rowCountForSlot(2));
        assertEquals(2, totalBankRowCount());
    }

    @Test
    void upsertAccountMaterials_insertsUpdatesOnConflictAndDeletesStaleRows() throws Exception {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        int refreshedItemId = 19721;
        int droppedItemId = 19722;

        MaterialStack refreshed = new MaterialStack(refreshedItemId, 5, 100, "Account");
        MaterialStack dropped = new MaterialStack(droppedItemId, 5, 50, null);
        AccountSync.upsertAccountMaterials(con, List.of(refreshed, dropped), ts(t1));

        // (a) Initial insert landed correctly, including a NULL binding case.
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT category, count, binding FROM account_materials WHERE item_id = " + refreshedItemId)) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("category"));
            assertEquals(100, rs.getInt("count"));
            assertEquals("Account", rs.getString("binding"));
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT binding FROM account_materials WHERE item_id = " + droppedItemId)) {
            assertTrue(rs.next());
            assertNull(rs.getString("binding"));
        }

        // Second sync run: refreshedItemId's count changed (overlapping key); droppedItemId is no
        // longer reported by this run at all.
        MaterialStack refreshedUpdated = new MaterialStack(refreshedItemId, 5, 250, "Account");
        AccountSync.upsertAccountMaterials(con, List.of(refreshedUpdated), ts(t2));

        // (b) Overlapping key updated the existing row rather than duplicating it.
        assertEquals(1, materialRowCount(refreshedItemId));
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count FROM account_materials WHERE item_id = " + refreshedItemId)) {
            assertTrue(rs.next());
            assertEquals(250, rs.getInt("count"));
        }

        // (c) droppedItemId's row (fetched_at = t1, predating this run's t2) was deleted by the
        // stale-row cleanup; refreshedItemId's row (fetched_at = t2) survives.
        assertEquals(0, materialRowCount(droppedItemId));
        assertEquals(1, materialRowCount(refreshedItemId));
    }

    @Test
    void upsertAccountRecipes_insertsUpdatesOnConflictAndDeletesStaleRows() throws Exception {
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        int refreshedRecipeId = 7319;
        int droppedRecipeId = 7320;

        AccountSync.upsertAccountRecipes(con, List.of(refreshedRecipeId, droppedRecipeId), ts(t1));

        // (a) Initial insert landed correctly.
        assertEquals(1, recipeRowCount(refreshedRecipeId));
        assertEquals(1, recipeRowCount(droppedRecipeId));

        // Second sync run: refreshedRecipeId is reported again (overlapping key, no other data to
        // change since account_recipes has no columns besides the key and fetched_at); droppedRecipeId
        // is no longer reported at all.
        AccountSync.upsertAccountRecipes(con, List.of(refreshedRecipeId), ts(t2));

        // (b) Overlapping key updated the existing row rather than duplicating it.
        assertEquals(1, recipeRowCount(refreshedRecipeId));

        // (c) droppedRecipeId's row (fetched_at = t1, predating this run's t2) was deleted by the
        // stale-row cleanup; refreshedRecipeId's row (fetched_at = t2) survives.
        assertEquals(0, recipeRowCount(droppedRecipeId));
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT fetched_at FROM account_recipes WHERE recipe_id = " + refreshedRecipeId)) {
            assertTrue(rs.next());
            assertEquals(ts(t2), rs.getTimestamp(1));
        }
    }

    private int slotCount(int slot) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count FROM account_bank WHERE slot = " + slot)) {
            assertTrue(rs.next());
            return rs.getInt("count");
        }
    }

    private int rowCountForSlot(int slot) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM account_bank WHERE slot = " + slot)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int totalBankRowCount() throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM account_bank")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int materialRowCount(int itemId) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM account_materials WHERE item_id = " + itemId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int recipeRowCount(int recipeId) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM account_recipes WHERE recipe_id = " + recipeId)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
