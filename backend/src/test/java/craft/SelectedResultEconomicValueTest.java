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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-checks the selected-result resolution value against the domain calculation economics. */
class SelectedResultEconomicValueTest {

    private static final int OUTPUT = 100;
    private static final int RAW = 200;

    @Test
    void mixedOwnedAndBoughtMaterialsReconcileTreeWithCalculationTotals() {
        Outcome outcome = evaluate(
                recipe(1, OUTPUT, List.of(ingredient(RAW, 2))),
                List.of(recipe(1, OUTPUT, List.of(ingredient(RAW, 2)))),
                Map.of(RAW, 3),
                Map.of(OUTPUT, quote(100, 120), RAW, quote(40, 40)),
                settings(true, true, false, 40));

        assertEquals(2, outcome.row().craftableCount);
        assertEquals(160, outcome.explanation().root().effectiveCostCopper(),
                "root cost includes three owned units and one purchased unit");
        assertEquals(160, calculationMaterialCost(outcome.row()),
                "the root must equal displayed own-material value plus purchase cost");
        assertEquals(calculationMaterialCost(outcome.row()), outcome.explanation().root().effectiveCostCopper());
    }

    @Test
    void allOwnedMaterialsUseTheirConsumedOpportunityValue() {
        Recipe target = recipe(2, OUTPUT, List.of(ingredient(RAW, 2)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(RAW, 2),
                Map.of(OUTPUT, quote(100, 120), RAW, quote(12, 12)), settings(true, false, false));

        assertEquals(24, outcome.explanation().root().effectiveCostCopper());
        assertEquals(24, calculationMaterialCost(outcome.row()));
    }

    @Test
    void allBoughtMaterialsUseTheConfiguredAcquisitionMode() {
        Recipe target = recipe(3, OUTPUT, List.of(ingredient(RAW, 2)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(),
                Map.of(OUTPUT, quote(100, 120), RAW, quote(30, 40)), settings(false, true, true, 60));

        assertEquals(60, outcome.row().buyCostCopper);
        assertEquals(30, outcome.row().materialPurchaseCosts.get(RAW).unitPriceCopper());
        assertEquals(60, outcome.row().materialPurchaseCosts.get(RAW).totalPriceCopper());
        assertEquals(60, outcome.explanation().root().effectiveCostCopper());
        assertEquals(60, calculationMaterialCost(outcome.row()));
    }

    @Test
    void nestedCraftCostsAreIncludedInParentExactlyOnce() {
        Recipe target = recipe(4, OUTPUT, List.of(ingredient(300, 2)));
        Recipe intermediate = recipe(5, 300, 2, List.of(ingredient(RAW, 2)));
        Outcome outcome = evaluate(target, List.of(target, intermediate), Map.of(RAW, 2),
                Map.of(OUTPUT, quote(100, 120), 300, quote(30, 40), RAW, quote(12, 12)),
                settings(true, false, false));

        CraftTraceNode child = outcome.explanation().root().children().get(0);
        assertEquals(24, child.effectiveCostCopper());
        assertEquals(24, outcome.explanation().root().effectiveCostCopper());
        assertEquals(child.effectiveCostCopper(), outcome.explanation().root().effectiveCostCopper(),
                "the ancestor includes descendants already and must not add them a second time");
    }

    @Test
    void nonTradableOwnedMaterialKeepsItsEstablishedZeroValue() {
        Recipe target = recipe(6, OUTPUT, List.of(ingredient(RAW, 2)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(RAW, 2),
                Map.of(OUTPUT, quote(100, 120)), settings(true, false, false));

        CraftTraceNode raw = outcome.explanation().root().children().get(0);
        assertTrue(raw.states().contains(ResolutionState.UNVALUED_NONTRADEABLE));
        assertEquals(Integer.valueOf(0), raw.effectiveCostCopper());
        assertEquals(Integer.valueOf(0), outcome.explanation().root().effectiveCostCopper());
    }

    @Test
    void valuedBoundOwnedMaterialReconcilesListWithFreshDetail() {
        // MID: 3 per craft from 10 x RAW(buy 6) => 60/3 = 20 per unit; 3 MID owned as account-bound.
        int mid = 300;
        Recipe midRecipe = recipe(8, mid, 3, List.of(ingredient(RAW, 10)));
        Recipe target = recipe(9, OUTPUT, List.of(ingredient(mid, 3)));
        List<CharacterCraftingProfile> roster = List.of(profile("Aria", "Artificer", 500));
        List<Recipe> recipes = List.of(target, midRecipe);
        Set<Integer> allowed = Set.of(8, 9);
        Map<Integer, PriceQuote> quotes = Map.of(OUTPUT, quote(500, 600), RAW, quote(6, 6));
        CraftingSettings s = settings(true, false, false);
        Map<Integer, Integer> bound = Map.of(mid, 3);

        CraftResult row = new CraftingPlanner().evaluateOneCoordinated(target, recipes, Map.of(), bound,
                Map.of(), roster, quotes, s, allowed);
        SingleCraftExplanation explanation = new SingleCraftExplainer().explainCoordinatedResult(
                target, recipes, Map.of(), bound, Map.of(), roster, quotes, s, allowed, row.craftableCount);

        assertEquals(1, row.craftableCount);
        assertEquals(0, row.buyCostCopper, "valuation must not buy RAW");
        assertEquals(60, row.totalMatsSellValueCopper, "3 bound MID at 20 each");
        CraftTraceNode midNode = explanation.root().children().get(0);
        assertEquals(3, inventoryQuantity(midNode, mid));
        assertEquals(0, boughtQuantity(midNode, mid));
        assertTrue(midNode.children().isEmpty() || midNode.children().stream()
                .allMatch(c -> boughtQuantity(c, RAW) == 0 && inventoryQuantity(c, RAW) == 0),
                "no ingredient purchase or consumption for owned bound stock");
        assertEquals(Integer.valueOf(60), explanation.root().effectiveCostCopper());
        assertEquals(calculationMaterialCost(row), explanation.root().effectiveCostCopper());
    }

    @Test
    void unvaluedBoundOwnedMaterialStaysFlaggedAndConsistent() {
        int mid = 300;
        Recipe target = recipe(9, OUTPUT, List.of(ingredient(mid, 2)));
        List<CharacterCraftingProfile> roster = List.of(profile("Aria", "Artificer", 500));
        Set<Integer> allowed = Set.of(9);
        CraftingSettings s = settings(true, false, false);
        Map<Integer, Integer> bound = Map.of(mid, 2);
        CraftResult row = new CraftingPlanner().evaluateOneCoordinated(target, List.of(target), Map.of(), bound,
                Map.of(), roster, Map.of(OUTPUT, quote(100, 120)), s, allowed);
        SingleCraftExplanation explanation = new SingleCraftExplainer().explainCoordinatedResult(
                target, List.of(target), Map.of(), bound, Map.of(), roster, Map.of(OUTPUT, quote(100, 120)),
                s, allowed, row.craftableCount);
        assertEquals(1, row.craftableCount);
        assertEquals(0, calculationMaterialCost(row));
        CraftTraceNode midNode = explanation.root().children().get(0);
        assertTrue(midNode.states().contains(ResolutionState.UNVALUED_NONTRADEABLE));
        assertEquals(Integer.valueOf(0), explanation.root().effectiveCostCopper());
    }

    @Test
    void unavailableRequiredPurchaseRemainsUnknownRatherThanZero() {
        Recipe target = recipe(7, OUTPUT, List.of(ingredient(RAW, 1)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(),
                Map.of(OUTPUT, quote(100, 120)), settings(false, true, false));

        assertEquals(0, outcome.row().craftableCount);
        assertNull(outcome.explanation().root().effectiveCostCopper());
        assertTrue(outcome.explanation().root().states().contains(ResolutionState.PRICE_UNAVAILABLE));
    }

    @Test
    void fullSelectedQuantityUsesConsumedStockAndPurchasesAcrossEveryCraft() {
        Recipe target = recipe(8, OUTPUT, List.of(ingredient(RAW, 2)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(RAW, 3),
                Map.of(OUTPUT, quote(100, 120), RAW, quote(40, 40)), settings(true, true, false, 40));

        assertEquals(2, outcome.row().craftableCount);
        assertEquals(2, outcome.explanation().root().requestedQuantity());
        assertEquals(3, outcome.explanation().root().children().get(0).inventoryQuantity());
        assertEquals(1, outcome.explanation().root().children().get(0).boughtQuantity());
        assertEquals(160, outcome.explanation().root().effectiveCostCopper());
        assertEquals(outcome.row().buyCostCopper + 120, outcome.explanation().root().effectiveCostCopper(),
                "three owned units at 40c plus one purchase at 40c");
    }

    @Test
    void sharedMaterialAcrossCraftingBranchesMatchesTheAggregatedPurchaseListAndCosts() {
        Recipe target = recipe(10, OUTPUT, List.of(ingredient(301, 1), ingredient(302, 1)));
        Recipe left = recipe(11, 301, List.of(ingredient(RAW, 10)));
        Recipe right = recipe(12, 302, List.of(ingredient(RAW, 10)));
        Outcome outcome = evaluate(target, List.of(target, left, right), Map.of(RAW, 15),
                Map.of(OUTPUT, quote(1000, 1100), 301, quote(500, 600),
                        302, quote(500, 600), RAW, quote(9, 10)),
                settings(true, true, false, 50));

        assertEquals(1, outcome.row().craftableCount);
        assertEquals(Map.of(RAW, 5), outcome.row().missingToBuy,
                "the second branch buys only the five units left after the first branch used stock");
        assertEquals(50, outcome.row().buyCostCopper);
        assertEquals(5, boughtQuantity(outcome.explanation().root(), RAW));
        assertEquals(20, requestedQuantity(outcome.explanation().root(), RAW));
        assertEquals(15, inventoryQuantity(outcome.explanation().root(), RAW));
        assertEquals(50, outcome.row().materialPurchaseCosts.get(RAW).totalPriceCopper());
        assertEquals(50, outcome.row().buyCostCopper,
                "the listed purchase cost must match the selected resolution's actual cash cost");
        assertEquals(outcome.row().buyCostCopper + outcome.row().totalMatsSellValueCopper,
                outcome.explanation().root().effectiveCostCopper());
    }

    @Test
    void reportedMaterialAllocationIsPreservedInShoppingListAndPurchaseCost() {
        Recipe target = recipe(13, OUTPUT, List.of(ingredient(RAW, 1425)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(RAW, 609),
                Map.of(OUTPUT, quote(10000, 11000), RAW, quote(9, 10)),
                settings(true, true, false, 8160));

        CraftTraceNode material = outcome.explanation().root().children().get(0);
        assertEquals(1425, material.requestedQuantity());
        assertEquals(609, material.inventoryQuantity());
        assertEquals(816, material.boughtQuantity());
        assertEquals(Map.of(RAW, 816), outcome.row().missingToBuy);
        assertEquals(8160, outcome.row().buyCostCopper);
        assertEquals(8160, outcome.row().materialPurchaseCosts.get(RAW).totalPriceCopper());
    }

    private static int boughtQuantity(CraftTraceNode node, int itemId) {
        return (node.itemId() == itemId ? node.boughtQuantity() : 0)
                + node.children().stream().mapToInt(child -> boughtQuantity(child, itemId)).sum();
    }

    private static int requestedQuantity(CraftTraceNode node, int itemId) {
        return (node.itemId() == itemId ? node.requestedQuantity() : 0)
                + node.children().stream().mapToInt(child -> requestedQuantity(child, itemId)).sum();
    }

    private static int inventoryQuantity(CraftTraceNode node, int itemId) {
        return (node.itemId() == itemId ? node.inventoryQuantity() : 0)
                + node.children().stream().mapToInt(child -> inventoryQuantity(child, itemId)).sum();
    }

    @Test
    void multiOutputRecipeScalesByExecutionsAndRequestsAllProducedOutputUnits() {
        Recipe target = recipe(9, OUTPUT, 2, List.of(ingredient(RAW, 1)));
        Outcome outcome = evaluate(target, List.of(target), Map.of(RAW, 2),
                Map.of(OUTPUT, quote(100, 120), RAW, quote(40, 40)), settings(true, true, false, 40));

        assertEquals(3, outcome.row().craftableCount);
        assertEquals(6, outcome.explanation().root().requestedQuantity());
        assertEquals(2, outcome.explanation().root().children().get(0).inventoryQuantity());
        assertEquals(1, outcome.explanation().root().children().get(0).boughtQuantity());
        assertEquals(120, outcome.explanation().root().effectiveCostCopper());
        assertEquals(120, outcome.row().buyCostCopper + 80,
                "two owned inputs at 40c and one purchase at 40c");
        assertEquals(outcome.row().buyCostCopper + 80, outcome.explanation().root().effectiveCostCopper());
        assertEquals(calculationMaterialCost(outcome.row()), outcome.explanation().root().effectiveCostCopper());
    }

    private static int calculationMaterialCost(CraftResult row) {
        return row.buyCostCopper + row.totalMatsSellValueCopper;
    }

    private static Outcome evaluate(Recipe target, List<Recipe> recipes, Map<Integer, Integer> inventory,
                                   Map<Integer, PriceQuote> quotes, CraftingSettings settings) {
        List<CharacterCraftingProfile> roster = List.of(profile("Aria", "Artificer", 500));
        Set<Integer> allowed = recipes.stream().map(recipe -> recipe.recipeId).collect(java.util.stream.Collectors.toSet());
        CraftResult row = new CraftingPlanner().evaluateOneCoordinated(target, recipes, inventory,
                Map.of(), Map.of(), roster, quotes, settings, allowed);
        SingleCraftExplanation explanation = new SingleCraftExplainer().explainCoordinatedResult(
                target, recipes, inventory, Map.of(), Map.of(), roster, quotes, settings, allowed,
                row.craftableCount);
        return new Outcome(row, explanation);
    }

    private static CraftingSettings settings(boolean own, boolean buying, boolean listingBuy) {
        return settings(own, buying, listingBuy, 0);
    }

    private static CraftingSettings settings(boolean own, boolean buying, boolean listingBuy, int budget) {
        return new CraftingSettings(own, buying, budget, false, listingBuy, false);
    }

    private record Outcome(CraftResult row, SingleCraftExplanation explanation) { }
}
