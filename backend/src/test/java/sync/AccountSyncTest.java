package sync;

import model.AccountScope;
import model.BankSlot;
import model.MaterialStack;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.BankRepository;
import repo.EnvConfig;
import repo.RecipeRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL integration test (docs/TEST_STRATEGY.md) for {@code AccountSync}'s account-scoped
 * bank, material-storage and recipe publications: insert, update on conflict, deletion of rows the
 * run no longer reports, and isolation of a second account holding the same identifiers. Runs the
 * production Flyway chain in a disposable, uniquely named schema; seeded rows are already-parsed
 * model records, so no GW2 API call is made.
 */
class AccountSyncTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("10000000-0000-0000-0000-00000000000b");
    private static final Clock T1 = Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC);
    private static final Clock T2 = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_accountsync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
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

    @Test
    void publishBank_insertsUpdatesOnConflictAndDeletesUnreportedSlotsForOneAccountOnly() throws Exception {
        BankSlot bound = new BankSlot(0, 111, 5, "Soulbound", "Some Hero", 3, 42, "{\"a\":1}");
        BankSlot unbound = new BankSlot(1, 222, 9, null, null, null, null, null);
        BankSlot dropped = new BankSlot(2, 333, 1, null, null, null, null, null);
        AccountSync.publishBank(con, A, List.of(bound, unbound, dropped), T1);
        AccountSync.publishBank(con, B, List.of(new BankSlot(1, 222, 77, null, null, null, null, null)), T1);

        assertEquals("111|5|Soulbound|Some Hero|3|42|true", slot(A, 0));
        assertEquals("222|9|null|null|null|null|false", slot(A, 1));

        // Second run: slot 0 loses its binding, slot 1's count changes, slot 2 is no longer reported.
        AccountSync.publishBank(con, A, List.of(new BankSlot(0, 111, 5, null, null, null, null, null),
                new BankSlot(1, 222, 20, null, null, null, null, null)), T2);

        assertEquals(2, new BankRepository().loadBankSlots(con, A).size());
        assertEquals("111|5|null|null|null|null|false", slot(A, 0));
        assertEquals("222|20|null|null|null|null|false", slot(A, 1));
        assertEquals("null", slot(A, 2), "a slot the run no longer reports is deleted");
        // The other account's identically numbered slot is neither updated nor deleted.
        assertEquals("222|77|null|null|null|null|false", slot(B, 1));
        assertEquals(1, new BankRepository().loadBankSlots(con, B).size());
    }

    @Test
    void publishMaterials_insertsUpdatesOnConflictAndDeletesUnreportedItemsForOneAccountOnly() throws Exception {
        AccountSync.publishMaterials(con, A, List.of(new MaterialStack(19721, 5, 100, "Account"),
                new MaterialStack(19722, 5, 50, null)), T1);
        AccountSync.publishMaterials(con, B, List.of(new MaterialStack(19722, 5, 9, null)), T1);

        assertEquals(100, materialCount(A, 19721));
        assertEquals(50, materialCount(A, 19722));
        assertNull(materialBinding(A, 19722));
        assertEquals("Account", materialBinding(A, 19721));

        AccountSync.publishMaterials(con, A, List.of(new MaterialStack(19721, 5, 250, "Account")), T2);

        assertEquals(250, materialCount(A, 19721));
        assertEquals(0, materialCount(A, 19722), "an item the run no longer reports is deleted");
        assertEquals(9, materialCount(B, 19722), "the other account's row for that item is untouched");
    }

    @Test
    void publishRecipes_insertsKeepsAndDeletesUnreportedRecipesForOneAccountOnly() throws Exception {
        RecipeRepository recipes = new RecipeRepository();
        AccountSync.publishRecipes(con, A, List.of(7319, 7320), T1);
        AccountSync.publishRecipes(con, B, List.of(7320), T1);
        assertEquals(Set.of(7319, 7320), recipes.loadKnownRecipeIds(con, A));

        AccountSync.publishRecipes(con, A, List.of(7319), T2);

        assertEquals(Set.of(7319), recipes.loadKnownRecipeIds(con, A));
        assertEquals(Set.of(7320), recipes.loadKnownRecipeIds(con, B));
    }

    /** One slot as item|count|binding|bound_to|charges|stats_id|hasAttrs, or "null" when absent. */
    private String slot(AccountScope scope, int slot) throws Exception {
        try (var ps = con.prepareStatement("SELECT item_id, count, binding, bound_to, charges, stats_id, "
                + "stats_attrs IS NOT NULL FROM account_bank WHERE account_id = ? AND slot = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setInt(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";
                return rs.getObject(1) + "|" + rs.getObject(2) + "|" + rs.getString(3) + "|" + rs.getString(4)
                        + "|" + rs.getObject(5) + "|" + rs.getObject(6) + "|" + rs.getBoolean(7);
            }
        }
    }

    private int materialCount(AccountScope scope, int itemId) throws Exception {
        try (var ps = con.prepareStatement("SELECT count FROM account_materials WHERE account_id = ? AND item_id = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String materialBinding(AccountScope scope, int itemId) throws Exception {
        try (var ps = con.prepareStatement("SELECT binding FROM account_materials WHERE account_id = ? AND item_id = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
