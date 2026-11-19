package sync.tp.relevance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import model.AccountScope;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Crafting Profit relevance definition, exercised against a real PostgreSQL in a disposable
 * schema (STORY-APP-014 acceptance criteria 2 and 6).
 *
 * <p>Two callers now share one definition: the refresh selection, which must keep picking exactly
 * the stale and missing ids it always did, and the coverage report the freshness envelope is built
 * from. The point of asserting both against one seeded snapshot is that they can never drift into
 * describing different item sets — a reported gap that the admitted work would not close, or
 * coverage claimed over items the refresh never considered, would both show up here.
 *
 * <p>The seeded tables are the minimal columns the definition reads, created and dropped by this
 * test, so it runs on an empty database and never touches the developer's own schema.
 */
class CraftingProfitItemCollectorTest {

    private final String schema = "test_profit_relevance_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ACCOUNT = "10000000-0000-0000-0000-00000000000a";
    private static final String OTHER = "10000000-0000-0000-0000-00000000000b";
    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT);
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement statement = con.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO account_registry (account_id) VALUES ('" + ACCOUNT + "'), ('" + OTHER + "')");
            statement.execute("INSERT INTO items (item_id, name) SELECT g, 'Item ' || g FROM generate_series(1, 60) g");
            statement.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('"
                    + ACCOUNT + "', 7, 'Crafter'), ('" + OTHER + "', 8, 'Other')");

            // Recipe 1 is unlocked on the account, recipe 2 is learned by a character, recipe 3 is
            // neither - so item 40 must stay out of the account's answers. Recipe 4 belongs to another
            // account only: its item 50 must never enter this account's required set.
            statement.execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + ACCOUNT + "', 1)");
            statement.execute("INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES ('"
                    + ACCOUNT + "', 7, 2)");
            statement.execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + OTHER + "', 4)");
            statement.execute("INSERT INTO recipes (recipe_id, output_item_id) VALUES (1, 10), (2, 20), (3, 40), (4, 50)");
            statement.execute("INSERT INTO recipe_ingredients (recipe_id, item_id) VALUES (1, 11), (2, 21), (3, 41)");
            // Item 12 is relevant but not tradeable, so it is outside the required set entirely.
            statement.execute("INSERT INTO recipe_ingredients (recipe_id, item_id) VALUES (1, 12)");
            statement.execute("INSERT INTO tp_tradeable_items (item_id) VALUES (10), (11), (20), (21), (40), (41), (50)");

            // 10 fresh; 11 stale; 20 has a row with no fetch instant at all; 21 has no row.
            statement.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, "
                    + "sell_quantity, sell_unit_price, fetched_at) "
                    + "VALUES (10, 400, 120, 500, 150, now() - interval '2 minutes')");
            statement.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, "
                    + "sell_quantity, sell_unit_price, fetched_at) "
                    + "VALUES (11, 900, 7, 1000, 9, now() - interval '3 hours')");
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (20, NULL)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (con == null) return;
        try (Statement statement = con.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    @Test
    void refreshSelectsEveryRelevantTradeableItemWhoseQuoteIsStaleOrAbsent() throws Exception {
        Set<Integer> selected = new CraftingProfitItemCollector().collect(con, SCOPE);

        assertEquals(Set.of(11, 20, 21), selected,
                "stale, undated and never-fetched relevant tradeable items, and nothing else");
        assertFalse(selected.contains(10), "a quote inside the ten-minute policy is not refetched");
        assertFalse(selected.contains(12), "a relevant item that is not tradeable has no quote to fetch");
        assertFalse(selected.contains(40), "an unknown recipe's output is not relevant");
        assertFalse(selected.contains(41), "nor is its ingredient");
    }

    @Test
    void coverageDescribesTheSameRequiredSetAndCountsAbsentQuotesAsUncovered() throws Exception {
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        CraftingProfitItemCollector.Coverage coverage = collector.coverage(con, SCOPE);

        assertEquals(4, coverage.requiredItems(),
                "items 10, 11, 20 and 21: the relevant tradeable set, the same one refresh reads");
        assertEquals(1, coverage.freshItems());
        assertEquals(1, coverage.staleItems(), "item 11");
        assertEquals(2, coverage.missingItems(),
                "item 20 has no fetch instant and item 21 has no row: neither is an answered price");
        assertEquals(coverage.staleItems() + coverage.missingItems(), coverage.uncoveredItems());
        assertFalse(coverage.covered());
        assertEquals(collector.collect(con, SCOPE).size(), coverage.uncoveredItems(),
                "a reported gap must be exactly what the admitted refresh would fetch");
    }

    @Test
    void coverageIsCompleteOnceEveryRequiredQuoteIsInsideThePolicy() throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET fetched_at = now() WHERE item_id IN (10, 11, 20)");
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (21, now())");
        }

        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        CraftingProfitItemCollector.Coverage coverage = collector.coverage(con, SCOPE);

        assertTrue(coverage.covered());
        assertEquals(4, coverage.requiredItems());
        assertEquals(4, coverage.freshItems());
        assertEquals(0, coverage.uncoveredItems());
        assertEquals(Set.of(), collector.collect(con, SCOPE), "nothing left to fetch");
    }

    @Test
    void newlyRelevantItemsEnterTheRequiredSetAsSoonAsAccountKnowledgeChanges() throws Exception {
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        assertEquals(4, collector.coverage(con, SCOPE).requiredItems());

        // The account learns recipe 3: its output and ingredient become required, and neither has a
        // quote, so the set cannot be reported as covered until they are fetched.
        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET fetched_at = now() WHERE item_id IN (10, 11, 20)");
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (21, now())");
            statement.execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + ACCOUNT + "', 3)");
        }

        CraftingProfitItemCollector.Coverage coverage = collector.coverage(con, SCOPE);
        assertEquals(6, coverage.requiredItems());
        assertEquals(2, coverage.missingItems());
        assertFalse(coverage.covered(),
                "changed account inputs that add relevant ids must prevent a final result");
        assertEquals(Set.of(40, 41), collector.collect(con, SCOPE),
                "and the refresh must select exactly those new ids");
    }

    @Test
    void twoReadsOfUnchangedQuotesReportTheSameQuoteVersion() throws Exception {
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();

        assertEquals(collector.coverage(con, SCOPE).quoteVersion(), collector.coverage(con, SCOPE).quoteVersion(),
                "an unchanged generation must keep its identity, or every request would look new");
        assertNotNull(collector.coverage(con, SCOPE).quoteVersion());
    }

    @Test
    void rewritingOneQuotesNumbersChangesTheQuoteVersionAndNoAggregate() throws Exception {
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        CraftingProfitItemCollector.Coverage before = collector.coverage(con, SCOPE);

        // Item 10's market moved and was refetched under the same instant: nothing the counts or the
        // oldest/newest boundaries report can change, but the calculation now reads a different
        // price, so this is a different input generation.
        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET sell_unit_price = 151 WHERE item_id = 10");
        }

        CraftingProfitItemCollector.Coverage after = collector.coverage(con, SCOPE);

        assertEquals(before.requiredItems(), after.requiredItems());
        assertEquals(before.freshItems(), after.freshItems());
        assertEquals(before.staleItems(), after.staleItems());
        assertEquals(before.missingItems(), after.missingItems());
        assertEquals(before.oldestFetchedAt(), after.oldestFetchedAt());
        assertEquals(before.newestFetchedAt(), after.newestFetchedAt());
        assertNotEquals(before.quoteVersion(), after.quoteVersion(),
                "a changed quote must change the version of the quotes the calculation read");
    }

    @Test
    void refetchingAnInteriorQuoteChangesTheQuoteVersionAndNoAggregate() throws Exception {
        // Item 20 is dated strictly between the oldest (item 11) and the newest (item 10) quote, and
        // stays stale either side of the change, so every count and both boundaries are unaffected.
        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET fetched_at = now() - interval '60 minutes' "
                    + "WHERE item_id = 20");
        }
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        CraftingProfitItemCollector.Coverage before = collector.coverage(con, SCOPE);

        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET fetched_at = now() - interval '30 minutes' "
                    + "WHERE item_id = 20");
        }

        CraftingProfitItemCollector.Coverage after = collector.coverage(con, SCOPE);

        assertEquals(before.requiredItems(), after.requiredItems());
        assertEquals(before.freshItems(), after.freshItems());
        assertEquals(before.staleItems(), after.staleItems());
        assertEquals(before.missingItems(), after.missingItems());
        assertEquals(before.oldestFetchedAt(), after.oldestFetchedAt());
        assertEquals(before.newestFetchedAt(), after.newestFetchedAt());
        assertNotEquals(before.quoteVersion(), after.quoteVersion(),
                "a quote refetched inside the reported boundaries is still a changed input");
    }

    @Test
    void anEmptyRelevantSetIsReportedAsCoveredWithNoQuoteInstants() throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.execute("DELETE FROM account_recipes WHERE account_id = '" + ACCOUNT + "'");
            statement.execute("DELETE FROM character_recipes WHERE account_id = '" + ACCOUNT + "'");
        }

        CraftingProfitItemCollector.Coverage coverage = new CraftingProfitItemCollector().coverage(con, SCOPE);

        assertEquals(0, coverage.requiredItems());
        assertTrue(coverage.covered(), "nothing is required, so nothing is uncovered");
        assertNull(coverage.oldestFetchedAt());
        assertNull(coverage.newestFetchedAt());
        assertNull(coverage.quoteVersion(), "no relevant quote was read, so there is none to version");
    }

    @Test
    void anotherAccountsKnowledgeNeverEntersTheRequiredSetAndGlobalSelectionNeedsNoAccount() throws Exception {
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();

        assertFalse(collector.collect(con, SCOPE).contains(50), "another account's recipe knowledge is not ours");
        assertEquals(Set.of(50), collector.collect(con, AccountScope.of(OTHER)));
        assertEquals(Set.of(11, 20, 21, 40, 41, 50), collector.collectGlobal(con),
                "the global selection is the whole shared graph's stale and absent tradeable quotes");

        try (Statement statement = con.createStatement()) {
            statement.execute("DELETE FROM account_registry");
        }
        assertEquals(Set.of(11, 20, 21, 40, 41, 50), collector.collectGlobal(con),
                "an empty account registry neither empties nor changes the global selection");
    }

    private void seedAutoLearnedRecipeWithoutUnlockRows() throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO recipes (recipe_id, output_item_id, flags) VALUES (5, 52, ARRAY['AutoLearned'])");
            statement.execute("INSERT INTO recipe_ingredients (recipe_id, item_id) VALUES (5, 53)");
            statement.execute("INSERT INTO tp_tradeable_items (item_id) VALUES (52), (53)");
        }
    }

    @Test
    void anAutoLearnedRecipeWithNoUnlockRowsIsRelevantAndCountedByCoverage() throws Exception {
        seedAutoLearnedRecipeWithoutUnlockRows();
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();

        assertEquals(Set.of(11, 20, 21, 52, 53), collector.collect(con, SCOPE),
                "the AutoLearned output and ingredient are requested while unquoted");
        assertEquals(Set.of(50, 52, 53), collector.collect(con, AccountScope.of(OTHER)),
                "AutoLearned is account-independent; another account's explicit unlock stays its own");
        assertFalse(collector.collect(con, SCOPE).contains(50));
        CraftingProfitItemCollector.Coverage coverage = collector.coverage(con, SCOPE);
        assertEquals(6, coverage.requiredItems());
        assertEquals(4, coverage.missingItems());
        assertEquals(collector.collect(con, SCOPE).size(), coverage.uncoveredItems());

        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (52, now()), (53, now())");
            statement.execute("UPDATE tp_prices SET fetched_at = now() WHERE item_id IN (11, 20)");
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (21, now())");
        }
        CraftingProfitItemCollector.Coverage fresh = collector.coverage(con, SCOPE);
        assertTrue(fresh.covered());
        assertEquals(6, fresh.freshItems());
        assertTrue(collector.collect(con, SCOPE).isEmpty());
    }

    @Test
    void changingOnlyAnAutoLearnedExclusiveQuoteChangesTheQuoteVersion() throws Exception {
        seedAutoLearnedRecipeWithoutUnlockRows();
        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, "
                    + "sell_unit_price, fetched_at) VALUES (52, 1, 100, 1, 200, now() - interval '1 minute')");
        }
        CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
        CraftingProfitItemCollector.Coverage before = collector.coverage(con, SCOPE);
        try (Statement statement = con.createStatement()) {
            statement.execute("UPDATE tp_prices SET sell_unit_price = 201 WHERE item_id = 52");
        }
        CraftingProfitItemCollector.Coverage after = collector.coverage(con, SCOPE);

        assertEquals(before.requiredItems(), after.requiredItems());
        assertEquals(before.newestFetchedAt(), after.newestFetchedAt());
        assertNotEquals(before.quoteVersion(), after.quoteVersion());
    }
}
