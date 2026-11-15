package craft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #223: per-item demand is cumulative and transactional (DOMAIN_SPEC 42). */
class CraftingMaterialDemandBoundsTest {

    private final CraftingPlanner planner = new CraftingPlanner();
    private final CraftingSettings settings = new CraftingSettings(true, true, 0, false, false, true);

    @ParameterizedTest
    @ValueSource(ints = {2_500, 2_501})
    void discoveryChecksTheWholeOwnedAndBoughtRequirementBeforeAcceptingIt(int quantity) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, quantity)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root), Map.of(200, 1_000),
                Map.of(), Map.of(100, quote(10_000, 10_000), 200, quote(2, 3)), settings, Set.of(1));

        if (quantity == 2_500) {
            assertAll(
                    () -> assertEquals(1, result.craftableCount),
                    () -> assertEquals(BlockedReason.NONE, result.blockedReason),
                    () -> assertEquals(Map.of(200, 1_500), result.missingToBuy),
                    () -> assertEquals(4_500, result.buyCostCopper),
                    () -> assertEquals(2_000, result.totalMatsSellValueCopper));
        } else {
            assertRejectedWholeAttempt(result);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1_250, 1_251})
    void siblingBranchesShareOneMaterialAllowance(int secondQuantity) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(201, 1)));
        Recipe first = recipe(2, 200, List.of(ingredient(300, 1_250)));
        Recipe second = recipe(3, 201, List.of(ingredient(300, secondQuantity)));
        List<Recipe> recipes = List.of(root, first, second);
        Map<Integer, PriceQuote> prices = Map.of(100, quote(10_000, 10_000), 300, quote(2, 3));

        CraftResult result = planner.evaluateSingleCraft(root, recipes, Map.of(300, 1_000), Map.of(),
                prices, settings, Set.of(1, 2, 3));

        if (secondQuantity == 1_250) {
            assertAll(
                    () -> assertEquals(1, result.craftableCount),
                    () -> assertEquals(BlockedReason.NONE, result.blockedReason),
                    () -> assertEquals(Map.of(300, 1_500), result.missingToBuy),
                    () -> assertEquals(4_500, result.buyCostCopper),
                    () -> assertEquals(2_000, result.totalMatsSellValueCopper));
        } else {
            assertRejectedWholeAttempt(result);
        }
    }

    @Test
    void profitCountsOwnedAndBoughtDemandAcrossAcceptedBatchesAndKeepsThePrefix() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1_250)));
        Map<Integer, PriceQuote> prices = Map.of(100, quote(10_000, 10_000), 200, quote(1, 2));

        CraftResult result = planner.evaluateOne(root, List.of(root), Map.of(200, 1_500), Map.of(),
                prices, settings, Set.of(1));

        assertAll(
                () -> assertEquals(2, result.craftableCount),
                () -> assertEquals(BlockedReason.MATERIAL_LIMIT, result.blockedReason),
                () -> assertEquals(Map.of(200, 1_000), result.missingToBuy),
                () -> assertEquals(2_000, result.buyCostCopper),
                () -> assertEquals(1_500, result.totalMatsSellValueCopper),
                () -> assertEquals(20_000, result.totalSellValueCopper),
                () -> assertEquals(13_500, result.totalProfitCopper));
    }

    @ParameterizedTest
    @ValueSource(ints = {2_500, 2_501})
    void abandonedRecipeAlternativesReleaseTheirDemandAndPurchaseCost(int trialDemand) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(300, 2_500)));
        Recipe losing = recipe(2, 200, List.of(ingredient(300, trialDemand)));
        Recipe winning = recipe(3, 200, List.of(ingredient(400, 1)));
        List<Recipe> recipes = List.of(root, losing, winning);
        Map<Integer, PriceQuote> prices = Map.of(300, quote(1, 1), 400, quote(1, 1));

        SingleCraftExplanation explanation = new SingleCraftExplainer().explainIndividual(root,
                recipes, Map.of(), Map.of(), prices, settings, Set.of(1, 2, 3));

        CraftTraceNode resolved = explanation.root();
        assertAll(
                () -> assertEquals(1, resolved.craftedQuantity()),
                () -> assertTrue(resolved.blockedReasons().isEmpty()),
                () -> assertEquals(2_501, resolved.cashCostCopper()),
                () -> assertEquals(3, resolved.children().getFirst().recipeId()),
                () -> assertEquals(2_500, resolved.children().get(1).boughtQuantity()));
    }

    @Test
    void directPurchaseCanReplaceAnOversizedRecursiveRecipe() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1)));
        Recipe oversized = recipe(2, 200, List.of(ingredient(300, 2_501)));

        SingleCraftExplanation explanation = new SingleCraftExplainer().explainIndividual(root,
                List.of(root, oversized), Map.of(), Map.of(), Map.of(200, quote(10, 10)),
                settings, Set.of(1, 2));

        CraftTraceNode ingredient = explanation.root().children().getFirst();
        assertEquals(1, explanation.root().craftedQuantity());
        assertEquals(10, explanation.root().cashCostCopper());
        assertEquals(1, ingredient.boughtQuantity());
        assertTrue(ingredient.children().isEmpty(), "the discarded craft path contributes nothing");
        assertTrue(ingredient.blockedReasons().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 1_001})
    void reusedBatchSurplusStillCountsAsDemandInAnotherBranch(int secondQuantity) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(201, 1)));
        Recipe first = recipe(2, 200, List.of(ingredient(400, 1_500)));
        Recipe second = recipe(3, 201, List.of(ingredient(400, secondQuantity)));
        Recipe batch = recipe(4, 400, 2_000, List.of(ingredient(500, 1)));

        CraftResult result = planner.evaluateSingleCraft(root, List.of(root, first, second, batch),
                Map.of(), Map.of(), Map.of(500, quote(1, 1)), settings, Set.of(1, 2, 3, 4));

        if (secondQuantity == 1_000) {
            assertEquals(1, result.craftableCount);
            assertEquals(BlockedReason.NONE, result.blockedReason);
            assertEquals(Map.of(500, 2), result.missingToBuy);
            assertEquals(2, result.buyCostCopper);
        } else {
            assertRejectedWholeAttempt(result);
        }
    }

    @Test
    void anOversizedMultipliedRequirementIsRejectedBeforeStockOrPriceIsUsed() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 2_500)));
        Recipe intermediate = recipe(2, 200, List.of(ingredient(300, Integer.MAX_VALUE)));
        PlanState state = new PlanState(Map.of(300, Integer.MAX_VALUE));
        PlannerContext context = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(List.of(root, intermediate)),
                Map.of(300, quote(Integer.MAX_VALUE, Integer.MAX_VALUE)), settings, Set.of(1, 2));

        ResolveResult result = new CraftingResolver().resolveOneCraft(root, context, state);

        assertFalse(result.getRoot().isFullySatisfied());
        assertEquals(BlockedReason.MATERIAL_LIMIT, result.getRoot().getBlockedReason());
        assertEquals(Map.of(300, Integer.MAX_VALUE), state.inventory);
        assertEquals(0, state.buyCostCopper);
    }

    @Test
    void rejectedRootRestoresOwnedStockSurplusDailyAllowanceAndDemandForTheNextAttempt() {
        int dailyItem = 43772;
        Recipe rejected = recipe(1, 100, List.of(ingredient(dailyItem, 1),
                ingredient(300, 2_500), ingredient(300, 1)));
        Recipe retry = recipe(2, 101, List.of(ingredient(dailyItem, 2), ingredient(300, 2_500)));
        Recipe daily = recipe(3, dailyItem, 2, List.of());
        List<Recipe> recipes = List.of(rejected, retry, daily);
        PlannerContext context = new PlannerContext(CraftingPlanner.buildRecipesByOutput(recipes),
                Map.of(), new CraftingSettings(true, false, 0, false, false, true), Set.of(1, 2, 3));
        PlanState state = new PlanState(Map.of(300, 2_500));
        CraftingResolver resolver = new CraftingResolver(true);

        ResolveResult failed = resolver.resolveOneCraft(rejected, context, state);
        assertFalse(failed.getRoot().isFullySatisfied());
        assertEquals(BlockedReason.MATERIAL_LIMIT, failed.getRoot().getBlockedReason());
        assertEquals(Map.of(300, 2_500), state.inventory);
        assertEquals(0, state.buyCostCopper);

        ResolveResult retried = resolver.resolveOneCraft(retry, context, state);
        assertTrue(retried.getRoot().isFullySatisfied(), "all rejected state must be reusable");
        ResolvedNeed dailyNeed = retried.getRoot().getChildren().getFirst();
        assertEquals(2, dailyNeed.getQtyCrafted());
        assertEquals(1, dailyNeed.getTrace().getCraftAttempt().getTrace().getCraftCount(),
                "the retry must make a fresh daily batch, with no leaked surplus or spent allowance");
    }

    @Test
    void eachCandidateRecipeHasAnIndependentMaterialAllowance() {
        Recipe first = recipe(1, 100, List.of(ingredient(300, 2_500)));
        Recipe second = recipe(2, 101, List.of(ingredient(300, 2_500)));

        Map<Integer, CraftResult> results = planner.evaluateAll(List.of(first, second),
                Map.of(300, 2_500), Map.of(), new CraftingSettings(true, false, 0, false, false, true),
                Set.of(1, 2));

        assertEquals(1, results.get(1).craftableCount);
        assertEquals(1, results.get(2).craftableCount);
        assertEquals(BlockedReason.MATERIAL_LIMIT, results.get(1).blockedReason);
        assertEquals(BlockedReason.MATERIAL_LIMIT, results.get(2).blockedReason);
    }

    private static void assertRejectedWholeAttempt(CraftResult result) {
        assertAll(
                () -> assertEquals(0, result.craftableCount),
                () -> assertEquals(BlockedReason.MATERIAL_LIMIT, result.blockedReason),
                () -> assertTrue(result.missingToBuy.isEmpty()),
                () -> assertEquals(0, result.buyCostCopper),
                () -> assertEquals(0, result.totalMatsSellValueCopper),
                () -> assertEquals(0, result.totalSellValueCopper));
    }
}
