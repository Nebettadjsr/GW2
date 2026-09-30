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
