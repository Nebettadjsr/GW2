package craft;

import org.junit.jupiter.api.Test;
import tradingpost.TradingPostFeePolicy;
import tradingpost.TradingPostSaleCalculator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * DOMAIN_SPEC.md sections 25-27 (resolved UD-011) as the planner produces them: profit is the gross
 * output sell value less the decided 15% Trading Post fee, purchased-material cost and
 * owned-material opportunity cost, while revenue and total sell value stay gross.
 *
 * <p>Every expected amount is written out by hand from that rule. Each case states its own gross
 * figures beside the profit so a fee applied twice, applied to a cost, or leaked into a displayed
 * value fails here rather than downstream.
 */
class CraftingPlannerProfitFeeTest {

    private static CraftingSettings ownMaterials(boolean listingSell) {
        return new CraftingSettings(true, false, 0, listingSell, false, false);
    }

    private static CraftResult evaluate(List<Recipe> recipes,
                                        Map<Integer, Integer> owned,
                                        Map<Integer, PriceQuote> tp,
                                        CraftingSettings settings,
                                        int recipeId) {
        return new CraftingPlanner()
                .evaluateAll(recipes, owned, tp, settings, Set.of(recipeId))
                .get(recipeId);
    }

    /**
     * DOMAIN_SPEC.md section 25's own worked example: 300c gross output, 204c of owned materials
     * given up, and therefore 51c of profit once the 45c fee is charged once.
     */
    @Test
    void theSpecExampleOfThreeHundredGrossLessTwoHundredAndFourOwnMaterialsGivesFiftyOne() {
        List<Recipe> recipes = List.of(recipe(1, 100, List.of(ingredient(200, 2))));
        Map<Integer, PriceQuote> tp = Map.of(
                100, quote(300, 500),
                200, quote(102, 150));

        CraftResult result = evaluate(recipes, Map.of(200, 2), tp, ownMaterials(false), 1);

        assertEquals(1, result.craftableCount);
        assertEquals(300, result.revenueCopper, "section 24's output revenue stays gross");
        assertEquals(204, result.matsSellValueCopper);
        assertEquals(0, result.buyCostCopper);
        assertEquals(51, result.profitCopper, "300 - 45 fee - 204 own materials");
        assertEquals(51, result.totalProfitCopper);
        assertEquals(300, result.totalSellValueCopper, "total sell value stays gross too");
    }

    /**
     * The listing-sell mode moves the gross sell price and the owned-material valuation together;
     * the fee follows the mode's own gross revenue rather than being fixed to the instant-sell one.
     */
    @Test
    void theListingSellModeChargesTheFeeOnItsOwnGrossRevenue() {
        List<Recipe> recipes = List.of(recipe(1, 100, List.of(ingredient(200, 2))));
        Map<Integer, PriceQuote> tp = Map.of(
                100, quote(300, 500),
                200, quote(102, 150));

        CraftResult result = evaluate(recipes, Map.of(200, 2), tp, ownMaterials(true), 1);

        assertEquals(500, result.revenueCopper, "the listing-sell price, gross");
        assertEquals(300, result.matsSellValueCopper, "the same mode values the owned materials");
        assertEquals(125, result.profitCopper, "500 - 75 fee - 300 own materials");
        assertEquals(125, result.totalProfitCopper);
        assertEquals(500, result.totalSellValueCopper);
    }

    /**
     * A recipe producing three items per execution over four executions: the fee is charged on the
     * execution's whole gross output once, and the total carries section 28's craft count without
     * charging it again.
     */
    @Test
    void aMultiOutputRecipeChargesOneFeePerExecutionAndScalesTheTotal() {
        List<Recipe> recipes = List.of(recipe(2, 300, 3, List.of(ingredient(400, 1))));
        Map<Integer, PriceQuote> tp = Map.of(
                300, quote(100, 130),
                400, quote(20, 25));

        CraftResult result = evaluate(recipes, Map.of(400, 4), tp, ownMaterials(false), 2);

        assertEquals(4, result.craftableCount);
        assertEquals(300, result.revenueCopper, "3 outputs at 100c, gross");
        assertEquals(20, result.matsSellValueCopper);
        assertEquals(235, result.profitCopper, "300 - 45 fee - 20 own materials");
        assertEquals(940, result.totalProfitCopper, "4 crafts of the same 235c");
        assertEquals(1_200, result.totalSellValueCopper, "4 x 300c gross, no fee deducted");
    }

    /**
     * Purchased materials: the fee is taken from the gross revenue only, and the buy cost it is
     * subtracted alongside is untouched by it. Here the purchases outweigh the sale, so the corrected
     * profit is a loss - which stays a loss rather than being clamped.
     */
    @Test
    void purchasedMaterialsAreSubtractedBesideTheFeeAndCanLeaveALoss() {
        List<Recipe> recipes = List.of(recipe(3, 500, List.of(ingredient(600, 2))));
        Map<Integer, PriceQuote> tp = Map.of(
                500, quote(300, 500),
                600, quote(102, 150));
        // Buying on, nothing owned, and a budget that pays for exactly two crafts at 300c each.
        CraftingSettings buying = new CraftingSettings(false, true, 600, false, false, false);

        CraftResult result = evaluate(recipes, Map.of(), tp, buying, 3);

        assertEquals(2, result.craftableCount);
        assertEquals(300, result.revenueCopper);
        assertEquals(0, result.matsSellValueCopper, "nothing owned was given up");
        assertEquals(600, result.buyCostCopper, "the total purchase cost, with no fee applied to it");
        assertEquals(-45, result.profitCopper, "300 - 45 fee - 300 bought materials");
        assertEquals(-90, result.totalProfitCopper);
        assertEquals(600, result.totalSellValueCopper, "gross proceeds, not netted against anything");
    }

    /** A craft whose gross revenue covers the fee and the materials exactly, and nothing more. */
    @Test
    void aCraftThatExactlyCoversItsFeeAndMaterialsProfitsNothing() {
        List<Recipe> recipes = List.of(recipe(4, 700, List.of(ingredient(800, 1))));
        Map<Integer, PriceQuote> tp = Map.of(
                700, quote(100, 130),
                800, quote(85, 90));

        CraftResult result = evaluate(recipes, Map.of(800, 1), tp, ownMaterials(false), 4);

        assertEquals(100, result.revenueCopper);
        assertEquals(85, result.matsSellValueCopper);
        assertEquals(0, result.profitCopper, "100 - 15 fee - 85 own materials");
        assertEquals(0, result.totalProfitCopper);
        assertEquals(100, result.totalSellValueCopper, "a zero profit is not a zero sell value");
    }

    /**
     * An output with no usable sell price reports the same known zero revenue as before; there is no
     * gross value to charge a fee on, so the profit is the material cost alone rather than a fee
     * invented from a missing price (DOMAIN_SPEC.md section 21).
     */
    @Test
    void anUnavailableOutputPriceCarriesNoFeeAndNoInventedRevenue() {
        List<Recipe> recipes = List.of(recipe(5, 900, List.of(ingredient(1000, 2))));
        Map<Integer, PriceQuote> tp = Map.of(1000, quote(40, 45));

        CraftResult result = evaluate(recipes, Map.of(1000, 2), tp, ownMaterials(false), 5);

        assertEquals(0, result.revenueCopper);
        assertEquals(80, result.matsSellValueCopper);
        assertEquals(-80, result.profitCopper, "no revenue, no fee, only the materials given up");
        assertEquals(-80, result.totalProfitCopper);
        assertEquals(0, result.totalSellValueCopper);
    }

    /**
     * A stored quote of zero or less is not a sale either (DOMAIN_SPEC.md section 21). The planner
     * reports the same non-positive revenue it always did and charges nothing on it, rather than
     * handing an impossible sell value to the fee policy.
     */
    @Test
    void aNonPositiveOutputQuoteIsNotChargedAFee() {
        List<Recipe> recipes = List.of(recipe(7, 1300, List.of(ingredient(1400, 1))));
        Map<Integer, PriceQuote> tp = Map.of(
                1300, quote(-5, 10),
                1400, quote(30, 35));

        CraftResult result = evaluate(recipes, Map.of(1400, 1), tp, ownMaterials(false), 7);

        assertEquals(-5, result.revenueCopper, "the stored quote is reported, not reinterpreted");
        assertEquals(30, result.matsSellValueCopper);
        assertEquals(-35, result.profitCopper, "-5 revenue less the 30c given up, and no fee");
    }

    /**
     * DOMAIN_SPEC.md section 25.1: the decided percentage model is not the explicit-sale transaction
     * model. A 3-copper gross sale is the case that tells them apart - both of
     * {@link TradingPostSaleCalculator}'s 1-copper minimums would bind, while section 25 charges
     * nothing at all, so the planner's profit is the full 3 copper.
     */
    @Test
    void aSmallCopperSaleCarriesNoMinimumFeeAtThePlanner() {
        List<Recipe> recipes = List.of(recipe(6, 1100, List.of(ingredient(1200, 1))));
        Map<Integer, PriceQuote> tp = Map.of(
                1100, quote(3, 4),
                // No sell listing of its own, so the owned unit is given up at a valuation of zero
                // (DOMAIN_SPEC.md section 11.2) and the profit is the sale alone.
                1200, CraftTestFixtures.noQuote());

        CraftResult result = evaluate(recipes, Map.of(1200, 1), tp, ownMaterials(false), 6);

        assertEquals(3, result.revenueCopper);
        assertEquals(0, result.matsSellValueCopper);
        assertEquals(3, result.profitCopper, "15% of 3c rounds to no fee at all");

        long transactionModelProfit = 3 - TradingPostSaleCalculator.forSale(3, 1).totalFeesCopper();
        assertEquals(1, transactionModelProfit, "the transaction model would charge both minimums");
        assertNotEquals(transactionModelProfit, result.profitCopper,
                "section 25.1: the minimum-fee transaction model is not applied to profitability");
        assertEquals(0, TradingPostFeePolicy.feeOn(result.revenueCopper));
    }
}
