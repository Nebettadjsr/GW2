package web;

import craft.CraftResult;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import web.dto.CraftingRowDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * STORY-FIX-001 regression: owned sellable materials carry their Trading Post opportunity cost
 * (DOMAIN_SPEC.md sections 11 and 25-27), owned bound materials do not (section 11.1), and the row
 * the API reports carries exactly the figures the domain computed.
 *
 * <p>Every expected copper amount below is calculated by hand from the quotes in the test, not read
 * back from the code under test. Fee: 15% of gross revenue (section 25).
 */
class OwnedMaterialCostRowRegressionTest {

    private static final int OUTPUT = 100;
    private static final int MATERIAL = 200;

    // Output sells instantly for 1000c gross -> 150c fee. Material: instant-sell 40c, instant-buy 50c.
    private static final Map<Integer, PriceQuote> TP = Map.of(
            OUTPUT, new PriceQuote(1000, 1200),
            MATERIAL, new PriceQuote(40, 50));

    private static final List<Recipe> RECIPES = List.of(
            new Recipe(1, OUTPUT, 1, 0, "Artificer", List.of(new Ingredient(MATERIAL, 3))));

    /** Own materials, buying allowed up to 50c in total (enough for exactly one purchased unit). */
    private static final CraftingSettings SETTINGS = new CraftingSettings(true, true, 50, false, false, false);

    private static CraftingRowDto row(Map<Integer, Integer> sellable, Map<Integer, Integer> bound,
                                      CraftingSettings settings) throws Exception {
        CraftResult result = new CraftingPlanner()
                .evaluateAll(RECIPES, sellable, bound, TP, settings, Set.of(1)).get(1);
        return CraftingRowMapper.toRow(RECIPES.get(0), result, Map.of(), TP);
    }

    @Test
    void fullyOwnedSellableMaterialsCostTheirTradingPostValueNotZero() throws Exception {
        CraftingRowDto row = row(Map.of(MATERIAL, 3), Map.of(), SETTINGS);

        // One craft: 3 owned x 40c = 120c opportunity cost, nothing purchased.
        assertEquals(1, row.craftableCount());
        assertEquals(0, row.buyCostCopper());
        assertEquals(120, row.matsSellValueCopper());
        assertEquals(120, row.totalMatsSellValueCopper());
        assertEquals(1000, row.revenueCopper());
        // 1000 - 150 fee - 0 bought - 120 owned
        assertEquals(730, row.profitCopper());
        assertEquals(730, row.totalProfitCopper());
    }

    @Test
    void mixedOwnedAndPurchasedMaterialsChargeBothCosts() throws Exception {
        CraftingRowDto row = row(Map.of(MATERIAL, 2), Map.of(), SETTINGS);

        // 2 owned x 40c = 80c opportunity cost, 1 bought x 50c = 50c cash; the next craft would need
        // 150c more and exceeds the 50c budget, so exactly one craft.
        assertEquals(1, row.craftableCount());
        assertEquals(50, row.buyCostCopper());
        assertEquals(80, row.matsSellValueCopper());
        assertEquals(80, row.totalMatsSellValueCopper());
        // 1000 - 150 - 50 - 80
        assertEquals(720, row.profitCopper());
        assertEquals(720, row.totalProfitCopper());
    }

    @Test
    void boundOwnedMaterialsCarryNoOpportunityCost() throws Exception {
        CraftingRowDto row = row(Map.of(), Map.of(MATERIAL, 3), SETTINGS);

        // Accepted bound-input behaviour (section 11.1): usable, never sold, so no opportunity cost.
        assertEquals(1, row.craftableCount());
        assertEquals(0, row.buyCostCopper());
        assertEquals(0, row.matsSellValueCopper());
        // 1000 - 150
        assertEquals(850, row.profitCopper());
        assertEquals(850, row.totalProfitCopper());
    }

    @Test
    void boundIsConsumedFirstAndOnlyTheSellableRemainderIsCosted() throws Exception {
        CraftingRowDto row = row(Map.of(MATERIAL, 1), Map.of(MATERIAL, 2), SETTINGS);

        // 2 bound (free) + 1 sellable x 40c.
        assertEquals(1, row.craftableCount());
        assertEquals(40, row.matsSellValueCopper());
        // 1000 - 150 - 40
        assertEquals(810, row.profitCopper());
    }

    @Test
    void multipleCraftsTotalTheOpportunityCostOfEveryCraft() throws Exception {
        CraftingSettings noBuying = new CraftingSettings(true, false, 0, false, false, false);
        CraftingRowDto row = row(Map.of(MATERIAL, 6), Map.of(), noBuying);

        // Two crafts, each 3 x 40c = 120c; per-craft profit 730, total 2 x 730.
        assertEquals(2, row.craftableCount());
        assertEquals(120, row.matsSellValueCopper());
        assertEquals(240, row.totalMatsSellValueCopper());
        assertEquals(730, row.profitCopper());
        assertEquals(1460, row.totalProfitCopper());
        assertEquals(2000, row.totalSellValueCopper());
    }
}
