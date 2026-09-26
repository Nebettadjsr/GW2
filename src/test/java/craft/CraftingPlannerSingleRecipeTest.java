package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.profile;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CraftingPlanner#evaluateOne} / {@link CraftingPlanner#evaluateOneCoordinated} are the
 * selected-recipe entry points the detail operation of TARGET_ARCHITECTURE.md section 13.2 needs.
 * They must be the table calculation restricted to one recipe, not a second set of rules, so every
 * assertion here compares them against the corresponding {@code evaluateAll*} entry rather than
 * against hand-computed economics (STORY-APP-012).
 */
class CraftingPlannerSingleRecipeTest {

    private static final Recipe TARGET = recipe(1, 100, List.of(ingredient(200, 2)));
    private static final Recipe SUB = recipe(2, 200, List.of(ingredient(300, 3)));
    private static final Recipe UNRELATED = recipe(3, 900, List.of(ingredient(300, 1)));

    private static final List<Recipe> RECIPES = List.of(TARGET, SUB, UNRELATED);
    private static final Set<Integer> ALLOWED = Set.of(1, 2, 3);

    private static final Map<Integer, PriceQuote> TP = Map.of(
            100, quote(1000, 1200),
            200, quote(80, 90),
            300, quote(10, 12));

    private static void assertSameResult(CraftResult fromTable, CraftResult fromOne) {
        assertEquals(fromTable.outputItemId, fromOne.outputItemId);
        assertEquals(fromTable.discipline, fromOne.discipline);
        assertEquals(fromTable.craftableCount, fromOne.craftableCount);
        assertEquals(fromTable.missingToBuy, fromOne.missingToBuy);
        assertEquals(fromTable.missingToBuyOne, fromOne.missingToBuyOne);
        assertEquals(fromTable.buyCostCopper, fromOne.buyCostCopper);
        assertEquals(fromTable.matsSellValueCopper, fromOne.matsSellValueCopper);
        assertEquals(fromTable.revenueCopper, fromOne.revenueCopper);
        assertEquals(fromTable.profitCopper, fromOne.profitCopper);
        assertEquals(fromTable.totalProfitCopper, fromOne.totalProfitCopper);
        assertEquals(fromTable.blockedReason, fromOne.blockedReason);
        assertNull(fromOne.tree, "the table path attaches no tree, so neither may the single-recipe path");
    }

    @Test
    void individualPathMatchesTheTableEntryAcrossEverySettingsCombination() {
        CraftingPlanner planner = new CraftingPlanner();
        Map<Integer, Integer> sellable = Map.of(200, 1, 300, 7);
        Map<Integer, Integer> bound = Map.of(300, 2);

        for (boolean useOwnMats : new boolean[]{false, true}) {
            for (boolean allowBuying : new boolean[]{false, true}) {
                CraftingSettings settings =
                        new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);

                CraftResult fromTable = planner
                        .evaluateAll(RECIPES, sellable, bound, TP, settings, ALLOWED)
                        .get(TARGET.recipeId);
                CraftResult fromOne = planner
                        .evaluateOne(TARGET, RECIPES, sellable, bound, TP, settings, ALLOWED);

                assertSameResult(fromTable, fromOne);
            }
        }
    }

    @Test
    void coordinatedPathMatchesTheTableEntryIncludingCharacterBoundInventory() {
        CraftingPlanner planner = new CraftingPlanner();
        List<CharacterCraftingProfile> roster =
                List.of(profile("Aria", "Artificer", 400), profile("Bran", "Artificer", 400));
        Map<Integer, Integer> sellable = Map.of(300, 4);
        Map<Integer, Integer> accountBound = Map.of(200, 1);
        Map<String, Map<Integer, Integer>> characterBound = Map.of("Bran", Map.of(300, 5));
        CraftingSettings settings = new CraftingSettings(true, true, 0, false, false, false);

        CraftResult fromTable = planner.evaluateAllCoordinated(
                RECIPES, sellable, accountBound, characterBound, roster, TP, settings, ALLOWED)
                .get(TARGET.recipeId);
        CraftResult fromOne = planner.evaluateOneCoordinated(
                TARGET, RECIPES, sellable, accountBound, characterBound, roster, TP, settings, ALLOWED);

        assertSameResult(fromTable, fromOne);
    }

    @Test
    void allowedRecipeIdsRestrictTheSingleRecipePathExactlyAsTheyRestrictTheTable() {
        CraftingPlanner planner = new CraftingPlanner();
        // The sub-recipe for item 200 is no longer allowed, so the requirement must be met some
        // other way - the restriction is part of the shared rules, not of the table loop.
        Set<Integer> allowed = Set.of(1, 3);
        CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, false);

        CraftResult fromTable = planner
                .evaluateAll(RECIPES, Map.of(300, 9), Map.of(), TP, settings, allowed)
                .get(TARGET.recipeId);
        CraftResult fromOne = planner
                .evaluateOne(TARGET, RECIPES, Map.of(300, 9), Map.of(), TP, settings, allowed);

        assertSameResult(fromTable, fromOne);
        assertEquals(0, fromOne.craftableCount);
    }

    @Test
    void initialInventoryIsNotConsumedByTheSingleRecipeEvaluation() {
        CraftingPlanner planner = new CraftingPlanner();
        Map<Integer, Integer> sellable = new java.util.HashMap<>(Map.of(200, 6));
        CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, false);

        planner.evaluateOne(TARGET, RECIPES, sellable, Map.of(), TP, settings, ALLOWED);

        assertEquals(Map.of(200, 6), sellable,
                "the caller's captured inventory must survive the evaluation unchanged");
    }
}
