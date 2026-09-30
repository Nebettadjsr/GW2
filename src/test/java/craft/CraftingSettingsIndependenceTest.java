package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingSettingsIndependenceTest {

    private static final int DAILY = 43772; // Charged Quartz Crystal
    private static final int BASE = 900_101;
    private static final int PARENT = 900_102;
    private static final int OUTPUT = 900_103;
    private static final List<Recipe> RECIPES = List.of(
            CraftTestFixtures.recipe(1, OUTPUT, List.of(CraftTestFixtures.ingredient(PARENT, 1))),
            CraftTestFixtures.recipe(2, PARENT, List.of(CraftTestFixtures.ingredient(DAILY, 1))),
            CraftTestFixtures.recipe(3, DAILY, List.of(CraftTestFixtures.ingredient(BASE, 1))),
            CraftTestFixtures.recipe(4, BASE, List.of()));
    private static final Map<Integer, PriceQuote> PRICES = Map.of(
            DAILY, CraftTestFixtures.quote(null, 10),
            PARENT, CraftTestFixtures.quote(null, 1_000),
            OUTPUT, CraftTestFixtures.quote(2_000, null));

    @Test
    void allEightSettingsCombinationsKeepInventoryBuyingAndDailyCraftingIndependent() {
        assertScenario(true, false, false, 1, 0);  // owned daily stock, no extra daily craft
        assertScenario(true, false, true, 2, 0);   // owned stock plus one daily craft
        assertScenario(true, true, false, 250, 249); // owned stock then buying
        assertScenario(true, true, true, 250, 248);  // owned, one daily craft, then buying
        assertScenario(false, false, true, 1, 0);  // inventory ignored; one daily craft
        assertScenario(false, true, false, 250, 250); // no daily craft; buy the daily input
        assertScenario(false, true, true, 250, 249); // one daily craft, then buying
        assertScenario(false, false, false, 0, 0); // no acquisition path
    }

    @Test
    void noBuyingPreventsTpPurchasesAtEveryRecursiveLevel() {
        CraftResult result = calculate(false, false, true, Map.of());

        assertEquals(1, result.craftableCount);
        assertEquals(Map.of(), result.missingToBuy);
        assertEquals(0, result.buyCostCopper);
    }

    @Test
    void buyingCanSupplyDailyIntermediateWhenDailyCraftingIsDisabled() {
        CraftResult result = calculate(false, true, false, Map.of());

        assertEquals(250, result.craftableCount);
        assertEquals(250, result.missingToBuy.get(DAILY));
        assertTrue(result.buyCostCopper > 0);
    }

    @Test
    void ownedDailyMaterialsRemainUsableWhenDailyCraftingIsDisabled() {
        CraftResult result = calculate(true, false, false, Map.of(DAILY, 1));

        assertEquals(1, result.craftableCount);
        assertEquals(Map.of(), result.missingToBuy);
    }

    @Test
    void disablingOwnMaterialsIgnoresInventoryEvenWhenThePlannerReceivesIt() {
        CraftingSettings settings = new CraftingSettings(false, false, 0, false, false, false);

        CraftResult result = new CraftingPlanner().evaluateOne(RECIPES.get(0), RECIPES,
                Map.of(DAILY, 1), Map.of(), PRICES, settings, Set.of(1, 2, 3, 4));

        assertEquals(0, result.craftableCount,
                "owned daily stock must not satisfy the ingredient while useOwnMats is disabled");
        assertEquals(Map.of(), result.missingToBuy);
    }

    @Test
    void failedRecursiveCandidateRollsBackDailyAllowanceForLaterSiblingRequirement() {
        int cycle = 900_104;
        Recipe root = CraftTestFixtures.recipe(10, OUTPUT, List.of(
                CraftTestFixtures.ingredient(PARENT, 1),
                CraftTestFixtures.ingredient(DAILY, 1)));
        Recipe failingParent = CraftTestFixtures.recipe(11, PARENT, List.of(
                CraftTestFixtures.ingredient(DAILY, 1),
                CraftTestFixtures.ingredient(cycle, 1)));
        Recipe dailyRecipe = CraftTestFixtures.recipe(12, DAILY, List.of());
        Recipe cycleRecipe = CraftTestFixtures.recipe(13, cycle, List.of(
                CraftTestFixtures.ingredient(cycle, 1)));
        Map<Integer, List<Recipe>> byOutput = Map.of(
                OUTPUT, List.of(root), PARENT, List.of(failingParent),
                DAILY, List.of(dailyRecipe), cycle, List.of(cycleRecipe));
        PlannerContext context = CraftTestFixtures.context(byOutput, Map.of(),
                new CraftingSettings(false, false, 0, false, false, true), Set.of(10, 11, 12, 13));
        PlanState state = CraftTestFixtures.emptyState();

        ResolvedNeed resolved = new CraftingResolver(true).resolveOneCraft(root, context, state).getRoot();
        ResolvedNeed acceptedRootCraft = resolved.getTrace().getCraftAttempt();

        assertEquals(0, acceptedRootCraft.getChildren().get(0).getQtyCrafted(),
                "the first parent's speculative daily craft fails with its recursive cycle");
        assertEquals(1, acceptedRootCraft.getChildren().get(1).getQtyCrafted(),
                "rollback restores the daily operation for the later sibling");
        assertEquals(1, state.dailyLeft(DAILY, 1),
                "the rejected root candidate also restores its later sibling's speculative allowance");
    }

    private static void assertScenario(boolean own, boolean buying, boolean daily,
                                       int expectedCrafts, int expectedBought) {
        CraftResult result = calculate(own, buying, daily, own ? Map.of(DAILY, 1) : Map.of());
        assertEquals(expectedCrafts, result.craftableCount,
                "useOwnMats=" + own + ", allowBuying=" + buying + ", allowDailyCrafts=" + daily);
        assertEquals(expectedBought == 0 ? Map.of() : Map.of(DAILY, expectedBought), result.missingToBuy,
                "purchase totals must follow allowBuying only");
    }

    private static CraftResult calculate(boolean own, boolean buying, boolean daily,
                                         Map<Integer, Integer> inventory) {
        CraftingSettings settings = new CraftingSettings(own, buying, 0, false, false, daily);
        return new CraftingPlanner().evaluateOne(RECIPES.get(0), RECIPES, inventory, Map.of(), PRICES,
                settings, Set.of(1, 2, 3, 4));
    }
}
