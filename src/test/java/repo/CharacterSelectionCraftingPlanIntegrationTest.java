package repo;

import craft.CraftResult;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md section 31.2) for STORY-DOM-015's
 * required trace of individual-character selection: real character-scoped owned-inventory rows
 * (repository) through the exact controller-facing split into sellable/bound quantity maps
 * (verbatim copy of {@code CraftingProfitController.reload(...)}'s split at the time of writing -
 * see CraftingProfitController.java lines ~104-115) into the exact production
 * {@link CraftingPlanner#evaluateAll(List, Map, Map, Map, CraftingSettings, Set)} overload that
 * both view controllers call, asserting the resulting {@link CraftResult} actually differs between
 * two characters whose relevant bound inventory differs (DOMAIN_SPEC.md section 2.2.1 / section 11.1).
 *
 * No dedicated JavaFX controller/view test layer exists in this repository (see STORY-DOM-012's
 * Result), and {@code CraftingProfitController} opens its own connection via
 * {@code repo.Db.open()} against the developer's configured database rather than an injectable
 * connection, so it cannot itself be pointed at this test's disposable schema without violating
 * TEST_STRATEGY.md section 9's "must not require the developer's normal database" rule. This test
 * instead exercises the same two production methods the controller calls, in the same order, with
 * the same real-Postgres-backed data shape, which is the closest available proof that a real
 * character-selection difference reaches the calculation without requiring a live UI session.
 *
 * Runs against a disposable, uniquely-named schema, following the same pattern as
 * {@link InventoryRepositoryBoundMaterialTest}.
 */
class CharacterSelectionCraftingPlanIntegrationTest {

    private final String schema = "test_charsel_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    private static final int SOULBOUND_ITEM_ID = 500;
    private static final int ORDINARY_ITEM_ID = 600;
    private static final int SOULBOUND_RECIPE_OUTPUT_ITEM_ID = 9001;
    private static final int CONTROL_RECIPE_OUTPUT_ITEM_ID = 9002;

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
                CREATE TABLE account_materials (
                    item_id     INTEGER PRIMARY KEY,
                    category    INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    fetched_at  TIMESTAMPTZ
                )
                """);
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
                CREATE TABLE characters (
                    character_id BIGSERIAL PRIMARY KEY,
                    name         TEXT NOT NULL UNIQUE
                )
                """);
            st.execute("""
                CREATE TABLE character_items (
                    character_id    BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    location        TEXT NOT NULL,
                    bag_index       INTEGER,
                    slot_index      INTEGER,
                    equipment_slot  TEXT,
                    item_id         INTEGER NOT NULL,
                    count           INTEGER NOT NULL DEFAULT 1,
                    binding         TEXT,
                    bound_to        TEXT,
                    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
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

    /** Mirrors CraftingProfitController.reload(...)'s sellable/bound split exactly. */
    private static Map<Integer, Integer>[] splitLikeController(Map<Integer, InventoryRepository.OwnedQuantity> owned) {
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> bound = new HashMap<>();
        for (var e : owned.entrySet()) {
            if (e.getValue().sellableQty() > 0) sellable.put(e.getKey(), e.getValue().sellableQty());
            if (e.getValue().boundQty() > 0) bound.put(e.getKey(), e.getValue().boundQty());
        }
        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] result = new Map[] { sellable, bound };
        return result;
    }

    @Test
    void changingSelectedCharacter_changesDisplayedResult_whenRelevantBoundInventoryDiffers() throws Exception {
        String selectedHero = "Selected Hero";
        String otherHero = "Other Hero";

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, '" + selectedHero + "')");
            st.execute("INSERT INTO characters (character_id, name) VALUES (2, '" + otherHero + "')");

            // Soulbound ingredient: only "Selected Hero" owns the 5 units this recipe needs.
            // Per DOMAIN_SPEC.md section 11.1, "Other Hero" must not see this as usable inventory.
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES (1, 'BAG', 0, 0, " + SOULBOUND_ITEM_ID + ", 5, 'Character', '" + selectedHero + "')");
        }

        Recipe soulboundRecipe = new Recipe(
                1, SOULBOUND_RECIPE_OUTPUT_ITEM_ID, 1, 0, "Artificer",
                List.of(new Ingredient(SOULBOUND_ITEM_ID, 5)));

        Recipe controlRecipe = new Recipe(
                2, CONTROL_RECIPE_OUTPUT_ITEM_ID, 1, 0, "Artificer",
                List.of(new Ingredient(ORDINARY_ITEM_ID, 3)));

        List<Recipe> recipes = List.of(soulboundRecipe, controlRecipe);
        Set<Integer> allowedRecipeIds = Set.of(1, 2);

        Map<Integer, PriceQuote> tp = Map.of(
                // Soulbound item has no Trading Post listing at all - realistic per section 11.1.
                SOULBOUND_ITEM_ID, new PriceQuote(null, null),
                // Ordinary tradable ingredient neither character owns - both must buy it identically.
                ORDINARY_ITEM_ID, new PriceQuote(null, 100));

        // useOwnMats=true, allowBuying=true, maxBuyCopper=500, instant sell/buy, not daily.
        CraftingSettings settings = new CraftingSettings(true, true, 500, false, false, false);

        CraftingPlanner planner = new CraftingPlanner();

        Map<Integer, InventoryRepository.OwnedQuantity> ownedBySelected =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, selectedHero);
        Map<Integer, Integer>[] splitSelected = splitLikeController(ownedBySelected);
        Map<Integer, CraftResult> resultsForSelected =
                planner.evaluateAll(recipes, splitSelected[0], splitSelected[1], tp, settings, allowedRecipeIds);

        Map<Integer, InventoryRepository.OwnedQuantity> ownedByOther =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, otherHero);
        Map<Integer, Integer>[] splitOther = splitLikeController(ownedByOther);
        Map<Integer, CraftResult> resultsForOther =
                planner.evaluateAll(recipes, splitOther[0], splitOther[1], tp, settings, allowedRecipeIds);

        // --- The soulbound-dependent recipe: relevant bound inventory differs -> result must differ.
        CraftResult selectedSoulboundResult = resultsForSelected.get(1);
        CraftResult otherSoulboundResult = resultsForOther.get(1);

        assertEquals(1, selectedSoulboundResult.craftableCount,
                "Selected Hero owns the 5 soulbound units this recipe needs and must be able to craft it once");
        assertEquals(0, selectedSoulboundResult.buyCostCopper,
                "the soulbound portion must never be bought - it is already owned by this character");
        assertEquals(0, selectedSoulboundResult.matsSellValueCopper,
                "bound materials carry no Trading Post opportunity cost per DOMAIN_SPEC.md section 11.1");

        assertEquals(0, otherSoulboundResult.craftableCount,
                "Other Hero does not own the soulbound ingredient and it has no Trading Post price, "
                        + "so the recipe must be blocked rather than silently craftable");

        // --- The control recipe: nothing relevant differs between the two characters -> identical result.
        CraftResult selectedControlResult = resultsForSelected.get(2);
        CraftResult otherControlResult = resultsForOther.get(2);

        assertEquals(selectedControlResult.craftableCount, otherControlResult.craftableCount,
                "neither character owns any of the ordinary ingredient, so results must be identical");
        assertEquals(selectedControlResult.buyCostCopper, otherControlResult.buyCostCopper,
                "identical results are valid here - this is a control case, not evidence of a defect");
        assertEquals(1, selectedControlResult.craftableCount);
        assertEquals(300, selectedControlResult.buyCostCopper);
    }
}
