package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code CraftResult.totalSellValueCopper} as DOMAIN_SPEC.md section 2.1.1 defines it: section 25's
 * per-execution output revenue for section 28's craftable count (STORY-WEB-008).
 *
 * <p>The recipe deliberately produces three items per execution, so a test can tell the output
 * quantity being applied once apart from it being applied twice or not at all, and the quotes are
 * far enough apart that the two sell-price modes cannot be confused. Section 25's rule that Crafting
 * Profit deducts no Trading Post selling fee is asserted as an exact equality rather than as a
 * tolerance, so any fee at all would fail.
 */
class CraftingPlannerTotalSellValueTest {

    /** Three outputs per execution, two of item 200 consumed each time. */
    private static final Recipe TARGET = recipe(1, 100, 3, List.of(ingredient(200, 2)));
    private static final List<Recipe> RECIPES = List.of(TARGET);
    private static final Set<Integer> ALLOWED = Set.of(1);

    private static final int OUTPUT_INSTANT_SELL = 1_000;
    private static final int OUTPUT_LISTING_SELL = 1_200;

    private static final Map<Integer, PriceQuote> TP = Map.of(
            100, quote(OUTPUT_INSTANT_SELL, OUTPUT_LISTING_SELL),
            200, quote(10, 12));

    /** Enough of item 200 for exactly five executions, with buying switched off. */
    private static final Map<Integer, Integer> OWNED = Map.of(200, 10);

    private static CraftingSettings settings(boolean listingSell) {
        return new CraftingSettings(true, false, 0, listingSell, false, false);
    }

    private static CraftResult evaluate(Map<Integer, Integer> owned,
                                        Map<Integer, PriceQuote> tp,
                                        CraftingSettings settings) {
        return new CraftingPlanner()
                .evaluateAll(RECIPES, owned, Map.of(), tp, settings, ALLOWED)
                .get(TARGET.recipeId);
    }

    @Test
    void multipleCraftsOfAMultiOutputRecipeSellEveryOutputOnceAtTheInstantSellPrice() {
        CraftResult result = evaluate(OWNED, TP, settings(false));

        assertEquals(5, result.craftableCount);
        assertEquals(OUTPUT_INSTANT_SELL * TARGET.outputCount, result.revenueCopper,
                "section 25's output revenue carries the recipe's output quantity and no fee");
        assertEquals(OUTPUT_INSTANT_SELL * TARGET.outputCount * 5, result.totalSellValueCopper,
                "the output quantity belongs in the total exactly once, through the per-craft revenue");
        assertEquals(result.revenueCopper * result.craftableCount, result.totalSellValueCopper);
    }

    @Test
    void theListingSellModeChangesTheTotalToThatModesPriceAndNothingElse() {
        CraftResult instant = evaluate(OWNED, TP, settings(false));
        CraftResult listing = evaluate(OWNED, TP, settings(true));

        assertEquals(OUTPUT_LISTING_SELL * TARGET.outputCount, listing.revenueCopper);
        assertEquals(OUTPUT_LISTING_SELL * TARGET.outputCount * 5, listing.totalSellValueCopper);
        assertEquals(instant.craftableCount, listing.craftableCount,
                "the sell-price mode decides the revenue, not how many crafts are possible");
    }

    @Test
    void theExistingProfitFiguresAreUnchangedBesideTheNewTotal() {
        CraftResult result = evaluate(OWNED, TP, settings(false));

        // Buying is off and every ingredient is owned, so the only per-craft deduction is the
        // opportunity cost of the two owned units of item 200 (DOMAIN_SPEC.md section 11.2).
        assertEquals(0, result.buyCostCopper);
        assertEquals(20, result.matsSellValueCopper);
        assertEquals(2_980, result.profitCopper);
        assertEquals(14_900, result.totalProfitCopper);
        assertEquals(result.profitCopper * result.craftableCount, result.totalProfitCopper,
                "section 27 is untouched by section 2.1.1's separate sell-value total");
    }

    @Test
    void zeroCraftsSellNothing() {
        CraftResult result = evaluate(Map.of(), TP, settings(false));

        assertEquals(0, result.craftableCount);
        assertEquals(0, result.totalSellValueCopper,
                "no craft was counted, so the crafts produce no sell value at all");
        assertEquals(OUTPUT_INSTANT_SELL * TARGET.outputCount, result.revenueCopper,
                "the per-execution revenue is still what one execution would return");
    }

    @Test
    void anUnavailableOutputPriceLeavesTheSameKnownZeroTheRevenueAlreadyReports() {
        Map<Integer, PriceQuote> withoutOutputQuote = Map.of(200, quote(10, 12));

        CraftResult result = evaluate(OWNED, withoutOutputQuote, settings(false));

        assertEquals(5, result.craftableCount, "the crafts are still possible; only the price is not");
        assertEquals(0, result.revenueCopper);
        assertEquals(0, result.totalSellValueCopper,
                "the total follows the revenue's own unavailable-price answer rather than inventing one");
    }

    @Test
    void theSellValueTotalIgnoresThePurchaseCostsTheProfitTotalSubtracts() {
        // Buying on, nothing owned: the same five crafts are now paid for rather than free.
        CraftingSettings buying = new CraftingSettings(false, true, 0, false, false, false);

        CraftResult result = evaluate(Map.of(), TP, buying);

        assertEquals(result.revenueCopper * result.craftableCount, result.totalSellValueCopper,
                "total sell value is proceeds, not a profit: no cost is netted off it");
        assertEquals(result.profitCopper * result.craftableCount, result.totalProfitCopper);
    }
}
