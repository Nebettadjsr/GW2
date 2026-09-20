import craft.*;
import org.junit.jupiter.api.Test;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CraftingBlockedRowsTest {
    private static final RecipeRepository.Recipe RECIPE = new RecipeRepository.Recipe(
            1, 100, 1, 0, "Artificer", List.of(new RecipeRepository.Ingredient(200, 2)));
    private static final List<RecipeRepository.Recipe> RECIPES = List.of(RECIPE);

    private static CraftingSettings settings(boolean buying, boolean listing, int budget) {
        return new CraftingSettings(true, buying, budget, false, listing, false);
    }

    private static Map<Integer, TpPriceRepository.TpQuote> quotes(Integer price, boolean absent) {
        Map<Integer, TpPriceRepository.TpQuote> quotes = new HashMap<>();
        quotes.put(100, new TpPriceRepository.TpQuote(100, 110));
        if (!absent) quotes.put(200, new TpPriceRepository.TpQuote(price, price));
        return quotes;
    }

    @Test
    void requiredUnpricedPurchaseMustSurviveProfitPreparation() {
        // Reproduces the documented hasZeroPricedBuy exclusion at its actual caller.
        CraftResult result = new CraftResult(100, "Artificer", 0,
                Map.of(200, 2), Map.of(), 0, 0, 100, 100, 0, null);
        for (boolean listing : List.of(false, true)) {
            for (Map<Integer, TpPriceRepository.TpQuote> tp : List.of(
                    quotes(null, true), quotes(null, false), quotes(0, false), quotes(-5, false))) {
                var rows = new CraftingProfitController().prepareRows(
                        RECIPES, RECIPES, Map.of(1, result), Map.of(), tp, settings(true, listing, 0));
                assertEquals(1, rows.size(), "Required unavailable purchase must not remove the row");
                assertTrue(rows.getFirst().missingSummary.contains("PRICE_UNAVAILABLE"));
            }
        }
    }

    @Test
    void plannerBlockedReasonsReachBothControllerRows() {
        for (boolean listing : List.of(false, true)) {
            for (Map<Integer, TpPriceRepository.TpQuote> tp : List.of(
                    quotes(null, true), quotes(null, false), quotes(0, false), quotes(-5, false))) {
                CraftingSettings settings = settings(true, listing, 0);
                var results = new CraftingPlanner().evaluateAll(RECIPES, Map.of(), tp, settings, Set.of(1));
                var profit = new CraftingProfitController().prepareRows(RECIPES, RECIPES, results, Map.of(), tp, settings);
                var discovery = new CraftingDiscoveryController().prepareRows(RECIPES, RECIPES, results, Map.of(), tp, settings);
                assertEquals(1, profit.size());
                assertEquals(1, discovery.size());
                assertTrue(profit.getFirst().missingSummary.contains("PRICE_UNAVAILABLE"));
                assertTrue(discovery.getFirst().missingSummary.contains("PRICE_UNAVAILABLE"));
            }
        }
    }

    @Test
    void validPurchaseKeepsExistingCalculations() {
        var tp = quotes(10, false);
        var settings = settings(true, false, 40);
        var results = new CraftingPlanner().evaluateAll(RECIPES, Map.of(), tp, settings, Set.of(1));
        var profit = new CraftingProfitController().prepareRows(RECIPES, RECIPES, results, Map.of(), tp, settings).getFirst();
        var discovery = new CraftingDiscoveryController().prepareRows(RECIPES, RECIPES, results, Map.of(), tp, settings).getFirst();
        assertEquals(2, profit.craftableCount);
        assertEquals(40, profit.buyCostCopper);
        assertEquals(100, profit.revenueCopper);
        assertEquals(80, profit.profitCopper);
        assertEquals(160, profit.totalProfitCopper);
        assertEquals(profit.buyCostCopper, discovery.buyCostCopper);
        assertEquals(profit.profitCopper, discovery.profitCopper);
    }
}
