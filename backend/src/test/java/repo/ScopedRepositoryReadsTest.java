package repo;

import craft.Recipe;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-COV-003: real-SQL coverage of the account-scoped read branches of the recipe, inventory and
 * character repositories, in a disposable schema built by the production Flyway chain. Expected
 * values are written out from the seeded rows below, not derived from the code under test.
 * Account A and account B deliberately share item ids, recipe ids and the character name "Shared".
 */
class ScopedRepositoryReadsTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-00000000000b");
    private static final AccountScope EMPTY = AccountScope.of("30000000-0000-0000-0000-00000000000c");

    private final String schema = "test_scoped_reads_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        con = connect();
        try (Statement s = con.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
        seed();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (con == null) return;
        try (Statement s = con.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }

    private void exec(String... statements) throws SQLException {
        try (Statement s = con.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }

    /**
     * A: materials item 100 x4 unbound, item 101 x1 Account-bound; bank item 100 x2 Account-bound,
     * item 102 x6 bound to "Alice"; Alice's bag item 100 x3 unbound, item 103 x5 bound to "Alice";
     * Bea's bag item 103 x7 bound to "Bea", item 104 x9 bound to a name outside A's roster;
     * a bank slot with NULL item_id.
     * B: material item 100 x50, character "Shared" bag item 100 x70, item 103 x80 bound to "Alice"
     * (a name that is A's character, not B's).
     */
    private void seed() throws SQLException {
        exec("INSERT INTO account_registry (account_id) VALUES ('" + A.accountId() + "'), ('" + B.accountId()
                        + "'), ('" + EMPTY.accountId() + "')",
                "INSERT INTO characters (account_id, character_id, name) VALUES ('" + A.accountId() + "', 1, 'Alice'),"
                        + " ('" + A.accountId() + "', 2, 'Bea'), ('" + B.accountId() + "', 3, 'Shared')",
                "INSERT INTO account_materials (account_id, item_id, count, binding) VALUES"
                        + " ('" + A.accountId() + "', 100, 4, NULL), ('" + A.accountId() + "', 101, 1, 'Account'),"
                        + " ('" + B.accountId() + "', 100, 50, NULL)",
                "INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES"
                        + " ('" + A.accountId() + "', 1, 100, 2, 'Account', NULL),"
                        + " ('" + A.accountId() + "', 2, 102, 6, 'Character', 'Alice'),"
                        + " ('" + A.accountId() + "', 3, NULL, NULL, NULL, NULL)",
                "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count,"
                        + " binding, bound_to) VALUES"
                        + " ('" + A.accountId() + "', 1, 'BAG', 0, 0, 100, 3, NULL, NULL),"
                        + " ('" + A.accountId() + "', 1, 'BAG', 0, 1, 103, 5, 'Character', 'Alice'),"
                        + " ('" + A.accountId() + "', 2, 'BAG', 0, 0, 103, 7, 'Character', 'Bea'),"
                        + " ('" + A.accountId() + "', 2, 'BAG', 0, 1, 104, 9, 'Character', 'Nobody'),"
                        + " ('" + B.accountId() + "', 3, 'BAG', 0, 0, 100, 70, NULL, NULL),"
                        + " ('" + B.accountId() + "', 3, 'BAG', 0, 1, 103, 80, 'Character', 'Alice')");
    }

    // ---- InventoryRepository ------------------------------------------------------------------

    @Test
    void ownedInventoryForSelectedCharacterSplitsSellableAndBoundWithinOneAccount() throws Exception {
        Map<Integer, InventoryRepository.OwnedQuantity> alice =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, A, "Alice");

        // item 100: 4 + 3 unbound sellable, 2 account-bound -> bound
        assertEquals(new InventoryRepository.OwnedQuantity(7, 2), alice.get(100));
        assertEquals(new InventoryRepository.OwnedQuantity(0, 1), alice.get(101));
        assertEquals(new InventoryRepository.OwnedQuantity(0, 6), alice.get(102));
        // Alice's own soulbound 5; Bea's bound 7 and B's 80 "Alice"-named row are not hers to use
        assertEquals(new InventoryRepository.OwnedQuantity(0, 5), alice.get(103));
        assertFalse(alice.containsKey(104));
        assertEquals(Set.of(100, 101, 102, 103), alice.keySet());
    }

    @Test
    void ownedInventoryForAnotherCharacterOrNoCharacterExcludesSoulboundRows() throws Exception {
        InventoryRepository repo = new InventoryRepository();

        Map<Integer, InventoryRepository.OwnedQuantity> bea = repo.loadOwnedInventoryForCharacter(con, A, "Bea");
        assertEquals(new InventoryRepository.OwnedQuantity(0, 7), bea.get(103));
        assertFalse(bea.containsKey(102));

        Map<Integer, InventoryRepository.OwnedQuantity> none = repo.loadOwnedInventoryForCharacter(con, A, null);
        assertEquals(Set.of(100, 101), none.keySet());
        assertEquals(new InventoryRepository.OwnedQuantity(7, 2), none.get(100));
    }

    @Test
    void ownedInventoryForSelectedCharacterNeverReadsAnotherAccountsRows() throws Exception {
        Map<Integer, InventoryRepository.OwnedQuantity> b =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, B, "Shared");

        assertEquals(Map.of(100, new InventoryRepository.OwnedQuantity(120, 0)), b);
        assertTrue(new InventoryRepository().loadOwnedInventoryForCharacter(con, EMPTY, "Alice").isEmpty());
    }

    @Test
    void coordinatedInventoryKeepsRosterOwnersSeparateAndDropsUnrosteredSoulbound() throws Exception {
        InventoryRepository.CoordinatedInventory inv =
                new InventoryRepository().loadOwnedInventoryForCharacters(con, A, Set.of("Alice", "Bea"));

        assertEquals(Map.of(100, 7), inv.sellable());
        assertEquals(Map.of(101, 1, 100, 2), inv.accountBound());
        assertEquals(Map.of(102, 6, 103, 5), inv.characterBound().get("Alice"));
        assertEquals(Map.of(103, 7), inv.characterBound().get("Bea"));
        assertEquals(Set.of("Alice", "Bea"), inv.characterBound().keySet());
    }

    @Test
    void coordinatedInventoryWithSmallerRosterOrOtherAccountIsIndependent() throws Exception {
        InventoryRepository repo = new InventoryRepository();

        InventoryRepository.CoordinatedInventory onlyBea = repo.loadOwnedInventoryForCharacters(con, A, Set.of("Bea"));
        assertEquals(Set.of("Bea"), onlyBea.characterBound().keySet());

        InventoryRepository.CoordinatedInventory b = repo.loadOwnedInventoryForCharacters(con, B, Set.of("Shared"));
        assertEquals(Map.of(100, 120), b.sellable());
        assertTrue(b.accountBound().isEmpty());
        assertTrue(b.characterBound().isEmpty());

        InventoryRepository.CoordinatedInventory empty = repo.loadOwnedInventoryForCharacters(con, EMPTY, Set.of("Alice"));
        assertTrue(empty.sellable().isEmpty() && empty.accountBound().isEmpty() && empty.characterBound().isEmpty());
    }

    @Test
    void scopedAndUnscopedInventoryPathsAreMutuallyRefusedOnTheWrongSchema() throws Exception {
        InventoryRepository repo = new InventoryRepository();
        // retired unscoped compatibility reads are refused on the managed schema, not re-enabled
        assertThrows(SQLException.class, () -> repo.loadOwnedInventory(con));
        assertThrows(SQLException.class, () -> repo.loadOwnedInventoryForCharacter(con, "Alice"));
        assertThrows(SQLException.class, () -> repo.loadOwnedInventoryForCharacters(con, Set.of("Alice")));

        // and the scoped reads refuse a legacy schema that has no account_id to scope by
        String legacy = schema + "_legacy";
        exec("CREATE SCHEMA " + legacy);
        try {
            con.setSchema(legacy);
            assertThrows(SQLException.class, () -> repo.loadOwnedInventory(con, A));
            assertThrows(SQLException.class, () -> repo.loadOwnedInventoryForCharacter(con, A, "Alice"));
            assertThrows(SQLException.class, () -> repo.loadOwnedInventoryForCharacters(con, A, Set.of("Alice")));
            assertThrows(SQLException.class, () -> new CharacterRepository().loadAllCharacterNames(con, A));
            assertThrows(SQLException.class, () -> new CharacterRepository().loadAllCharacterCrafting(con, A));
            assertThrows(SQLException.class, () -> new RecipeRepository().loadKnownRecipeIds(con, A));
        } finally {
            con.setSchema(schema);
            exec("DROP SCHEMA " + legacy + " CASCADE");
        }
    }

    // ---- CharacterRepository ------------------------------------------------------------------

    @Test
    void characterRosterAndCraftingAreScopedAndOrdered() throws Exception {
        exec("INSERT INTO character_crafting (account_id, character_id, discipline, rating, is_active) VALUES"
                + " ('" + A.accountId() + "', 1, 'Cook', 400, true),"
                + " ('" + A.accountId() + "', 2, 'Cook', 500, false),"
                + " ('" + A.accountId() + "', 1, 'Armorsmith', 150, false),"
                + " ('" + B.accountId() + "', 3, 'Cook', 25, true)");
        CharacterRepository repo = new CharacterRepository();

        assertEquals(List.of("Alice", "Bea"), repo.loadAllCharacterNames(con, A));
        assertEquals(List.of("Shared"), repo.loadAllCharacterNames(con, B));
        assertTrue(repo.loadAllCharacterNames(con, EMPTY).isEmpty());

        List<CharacterRepository.DiscRow> rows = repo.loadAllCharacterCrafting(con, A);
        // ordered by discipline, then rating descending
        assertEquals(List.of("Armorsmith/Alice/150/false", "Cook/Bea/500/false", "Cook/Alice/400/true"),
                rows.stream().map(r -> r.discipline + "/" + r.charName + "/" + r.rating + "/" + r.active).toList());
        assertEquals(1, repo.loadAllCharacterCrafting(con, B).size());
        assertEquals("Shared", repo.loadAllCharacterCrafting(con, B).get(0).charName);
        assertTrue(repo.loadAllCharacterCrafting(con, EMPTY).isEmpty());
    }

    @Test
    void unscopedCharacterReadsAreRefusedOnTheManagedSchema() {
        CharacterRepository repo = new CharacterRepository();
        assertThrows(SQLException.class, () -> repo.loadAllCharacterCrafting(con));
    }

    // ---- RecipeRepository ---------------------------------------------------------------------

    private void seedRecipes() throws SQLException {
        exec("INSERT INTO items (item_id, name) VALUES (1, 'In1'), (2, 'In2'), (10, 'Out10'), (11, 'Out11'),"
                        + " (12, 'Out12'), (13, 'Out13')",
                "INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines, flags) VALUES"
                        + " (1, 10, 1, 0, ARRAY['Cook'], NULL),"
                        + " (2, 11, 2, 25, ARRAY['Cook','Chef'], ARRAY[]::text[]),"
                        + " (3, 12, 1, 50, ARRAY['Armorsmith'], ARRAY['LearnedFromItem']),"
                        + " (4, 13, 1, 75, NULL, NULL)",
                "INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, 1, 3), (2, 1, 1), (2, 2, 4)",
                // A knows 1 (account) and 3 (via Bea); B knows 2 via its own character
                "INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + A.accountId() + "', 1)",
                "INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES"
                        + " ('" + A.accountId() + "', 2, 3), ('" + B.accountId() + "', 3, 2)");
    }

    @Test
    void knownRecipeIdsAreScopedToEachAccountAndEmptyWhenNothingIsPublished() throws Exception {
        seedRecipes();
        RecipeRepository repo = new RecipeRepository();

        assertEquals(Set.of(1, 3), repo.loadKnownRecipeIds(con, A));
        assertEquals(Set.of(2), repo.loadKnownRecipeIds(con, B));
        assertEquals(Set.of(), repo.loadKnownRecipeIds(con, EMPTY));
    }

    @Test
    void scopedRecipeLoadsReturnOnlyRecipesKnownToThatAccountWithExactMapping() throws Exception {
        seedRecipes();
        RecipeRepository repo = new RecipeRepository();

        List<Recipe> allA = repo.loadRecipes(con, A, "All");
        assertEquals(List.of(1, 3), allA.stream().map(r -> r.recipeId).toList());
        Recipe one = allA.get(0);
        assertEquals(10, one.outputItemId);
        assertEquals(1, one.outputCount);
        assertEquals("Cook", one.disciplinesText);
        assertEquals(1, one.ingredients.size());
        assertEquals(1, one.ingredients.get(0).itemId);
        assertEquals(3, one.ingredients.get(0).count);
        assertTrue(allA.get(1).ingredients.isEmpty());

        // discipline filter: A knows no Cook recipe other than 1
        assertEquals(List.of(1), repo.loadRecipes(con, A, "Cook").stream().map(r -> r.recipeId).toList());
        assertEquals(List.of(2), repo.loadRecipes(con, B, "Chef").stream().map(r -> r.recipeId).toList());
        Recipe two = repo.loadRecipes(con, B, "All").get(0);
        assertEquals("Cook, Chef", two.disciplinesText);
        assertEquals(2, two.ingredients.size());
        assertTrue(repo.loadRecipes(con, EMPTY, "All").isEmpty());
    }

    @Test
    void characterScopedRecipeLoadAlwaysFiltersByDisciplineAndCountsAnyCharacterOfTheAccount() throws Exception {
        seedRecipes();
        RecipeRepository repo = new RecipeRepository();

        // recipe 3 was unlocked by Bea but counts as known for Alice (account-wide, DQ-010)
        assertEquals(List.of(3), repo.loadRecipesForCharacter(con, A, "Alice", "Armorsmith").stream()
                .map(r -> r.recipeId).toList());
        // "All" is not a wildcard on the character-scoped family: no recipe lists the discipline "All"
        assertTrue(repo.loadRecipesForCharacter(con, A, "Alice", "All").isEmpty());
        assertTrue(repo.loadRecipesForCharacter(con, B, "Shared", "Armorsmith").isEmpty());
        assertTrue(repo.loadRecipesForCharacter(con, EMPTY, "Alice", "Cook").isEmpty());
    }

    @Test
    void missingDiscoverableRecipesExcludeKnownFlaggedAndOtherDisciplinesPerAccount() throws Exception {
        seedRecipes();
        RecipeRepository repo = new RecipeRepository();

        // discoverable = null or empty flags: 1, 2, 4 (3 is flagged). A knows 1 -> missing 2, 4.
        assertEquals(List.of(2, 4), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, A, "Alice", "All"));
        assertEquals(List.of(2), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, A, "Alice", "Cook"));
        assertEquals(List.of(), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, A, "Alice", "Armorsmith"));
        // null discipline behaves as "All"
        assertEquals(List.of(2, 4), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, A, "Alice", null));
        // B knows 2 only
        assertEquals(List.of(1, 4), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, B, "Shared", "All"));
        // an account with nothing published is missing every discoverable recipe
        assertEquals(List.of(1, 2, 4), repo.loadMissingDiscoverableRecipeIdsForCharacter(con, EMPTY, "X", "All"));
    }

    @Test
    void unscopedRecipeReadsAreRefusedOnTheManagedSchema() {
        RecipeRepository repo = new RecipeRepository();
        assertThrows(SQLException.class, () -> repo.loadRecipes(con, "All"));
        assertThrows(SQLException.class, () -> repo.loadRecipesForCharacter(con, "Alice", "Cook"));
        assertThrows(SQLException.class, () -> repo.loadMissingDiscoverableRecipeIdsForCharacter(con, "Alice", "Cook"));
    }

    @Test
    void globalRecipeGraphReadIsNotAccountScoped() throws Exception {
        seedRecipes();
        assertEquals(List.of(1, 2, 3, 4), new RecipeRepository().loadAllRecipes(con).stream().map(r -> r.recipeId).toList());
    }
}
