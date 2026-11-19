package craft;

import org.junit.jupiter.api.Test;
import tradingpost.TradingPostFeePolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approved Research Note estimate (issue #372): wallet Notes are valued from the cheaper fully priced actual Peer
 * Reviewed Research recipe (15120 / 15131, 150 Notes each); produced Notes stay charged by their batches only.
 */
class ResearchNoteEstimateTest {

    private static final int NOTE = CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID;
    private static final int PRR = CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID;
    private static final int OUTPUT = 97487;

    private static final Recipe CONSUMER = new Recipe(13839, OUTPUT, 1, 400, "Artificer",
            List.of(ingredient(97102, 4)), List.of(new CurrencyRequirement(NOTE, 30)));
    private static final Recipe ONE_NOTE = new Recipe(13841, OUTPUT, 1, 400, "Artificer",
            List.of(ingredient(97102, 1)), List.of(new CurrencyRequirement(NOTE, 1)));
    private static final Recipe PRR_A = new Recipe(15120, PRR, 1, 0, "Artificer", List.of(ingredient(1001, 4)));
    private static final Recipe PRR_B = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(ingredient(2001, 4)));
    private static final CraftingSettings OWN_ONLY = new CraftingSettings(true, false, 0, false, false, true);
    private static final CraftingSettings BUYING = new CraftingSettings(true, true, 0, false, false, true);
    private static final CraftingSettings BUY_MODE = new CraftingSettings(true, false, 0, false, true, true);

    private static Map<Integer, PriceQuote> tp(PriceQuote a, PriceQuote b) {
        Map<Integer, PriceQuote> tp = new HashMap<>();
        tp.put(OUTPUT, quote(5000, 6000));
        if (a != null) tp.put(1001, a);
        if (b != null) tp.put(2001, b);
        return tp;
    }

    private static CraftResult run(Recipe consumer, Map<Integer, PriceQuote> tp, Map<Integer, Integer> wallet,
                                   Map<Integer, Integer> stock, CraftingSettings settings) {
        List<Recipe> recipes = List.of(consumer, PRR_A, PRR_B);
        return new CraftingPlanner().withWallet(wallet)
                .evaluateAll(recipes, stock, Map.of(), tp, settings, Set.of(consumer.recipeId, 15120, 15131))
                .get(consumer.recipeId);
    }

    private static long netPerCraft(CraftResult r) {
        return r.revenueCopper - TradingPostFeePolicy.feeOn(r.revenueCopper);
    }

    @Test
    void cheaperVariantIsTheBasisWhicheverItIs() {
        // A = 4 x 100 = 400 per 150 Notes, B = 4 x 60 = 240: B is cheaper. 90 wallet Notes = ceil(90*240/150) = 144.
        CraftResult r = run(CONSUMER, tp(quote(100, 100), quote(60, 60)), Map.of(NOTE, 90), Map.of(97102, 100), OWN_ONLY);
        assertEquals(3, r.craftableCount);
        assertEquals(15131, r.noteEstimate.basisRecipeId());
        assertEquals(144, r.estimatedNotesCostTotalCopper);
        assertEquals(48, r.estimatedNotesCostPerCraftCopper, "30 Notes * 240 / 150");
        assertEquals(netPerCraft(r) - 48, r.profitCopper);
        assertEquals(3 * netPerCraft(r) - 144, r.totalProfitCopper);
        assertFalse(r.unpricedCurrency);
        assertEquals(Map.of(NOTE, 90), r.currencyTotal, "exact currency quantity stays visible");

        CraftResult flipped = run(CONSUMER, tp(quote(40, 40), quote(60, 60)), Map.of(NOTE, 90), Map.of(97102, 100), OWN_ONLY);
        assertEquals(15120, flipped.noteEstimate.basisRecipeId());
        assertEquals(96, flipped.estimatedNotesCostTotalCopper, "ceil(90 * 160 / 150)");
    }

    @Test
    void aMissingQuoteInOneVariantFallsBackAndBothVariantsStayListed() {
        CraftResult r = run(CONSUMER, tp(quote(100, 100), null), Map.of(NOTE, 90), Map.of(97102, 100), OWN_ONLY);
        assertEquals(15120, r.noteEstimate.basisRecipeId());
        assertEquals(240, r.estimatedNotesCostTotalCopper);
        assertEquals(2, r.noteEstimate.variants().size());
        assertNull(r.noteEstimate.variants().get(1).batchCostCopper(), "the unpriced variant is shown, never zero");
    }

    @Test
    void whenNeitherVariantCanBePricedNotesStayUnpricedAndConditional() {
        CraftResult r = run(CONSUMER, tp(null, null), Map.of(NOTE, 90), Map.of(97102, 100), OWN_ONLY);
        assertEquals(3, r.craftableCount);
        assertTrue(r.unpricedCurrency);
        assertEquals(0, r.estimatedNotesCostTotalCopper);
        assertNull(r.noteEstimate);
        assertEquals(3 * netPerCraft(r), r.totalProfitCopper, "no invented zero cost is deducted either way");
    }

    @Test
    void buyPriceModeSelectsTheBuyUnit() {
        // buy 50 / sell 100 for A, buy 70 / sell 10 for B. Sell mode: B (40/batch) wins; buy mode: A (200) wins.
        Map<Integer, PriceQuote> prices = tp(quote(50, 100), quote(70, 10));
        CraftResult sell = run(CONSUMER, prices, Map.of(NOTE, 150), Map.of(97102, 100), OWN_ONLY);
        assertEquals(15131, sell.noteEstimate.basisRecipeId());
        assertEquals(40, sell.estimatedNotesCostTotalCopper);
        CraftResult buy = run(CONSUMER, prices, Map.of(NOTE, 150), Map.of(97102, 100), BUY_MODE);
        assertEquals(15120, buy.noteEstimate.basisRecipeId());
        assertEquals(200, buy.estimatedNotesCostTotalCopper);
    }

    @Test
    void fractionalRateIsAggregatedThenRoundedUpOnce() {
        // A = 400c per 150 Notes (2.67c each). 7 crafts of 1 Note: total ceil(7*400/150) = 19, not 7 * 3 = 21.
        CraftResult r = run(ONE_NOTE, tp(quote(100, 100), null), Map.of(NOTE, 7), Map.of(97102, 100), OWN_ONLY);
        assertEquals(7, r.craftableCount);
        assertEquals(19, r.estimatedNotesCostTotalCopper);
        assertEquals(3, r.estimatedNotesCostPerCraftCopper, "ceil(1 * 400 / 150)");
        assertEquals(7 * netPerCraft(r) - 19, r.totalProfitCopper);
    }

    @Test
    void mixedWalletAndProducedChargesTheBatchOnceAndEstimatesOnlyTheWalletUnits() {
        // 45 wallet Notes + one bought batch of 150 (400c cash): 195 Notes = 6 crafts, surplus 15 reused.
        CraftResult r = run(CONSUMER, tp(quote(100, 100), null), Map.of(NOTE, 45), Map.of(97102, 24), BUYING);
        assertEquals(6, r.craftableCount);
        assertEquals(400, r.buyCostCopper, "the full batch is charged once as an ordinary purchase");
        assertEquals(45, r.estimatedNotesTotal);
        assertEquals(120, r.estimatedNotesCostTotalCopper);
        assertEquals(6 * netPerCraft(r) - 400 - 120, r.totalProfitCopper);
        assertEquals(Map.of(NOTE, 180), r.currencyTotal);
    }

    @Test
    void producedOnlyNotesAreNotEstimatedAgain() {
        CraftResult r = run(CONSUMER, tp(quote(100, 100), null), Map.of(), Map.of(97102, 100), BUYING);
        assertTrue(r.craftableCount > 0);
        assertEquals(0, r.estimatedNotesTotal);
        assertEquals(0, r.estimatedNotesCostTotalCopper);
        assertNull(r.noteEstimate);
        assertFalse(r.unpricedCurrency);
    }

    @Test
    void aRejectedBudgetAttemptLeavesNoEstimateOrCostBehind() {
        CraftingSettings budget = new CraftingSettings(true, true, 300, false, false, true);
        CraftResult r = run(CONSUMER, tp(quote(100, 100), null), Map.of(NOTE, 10), Map.of(97102, 100), budget);
        assertEquals(0, r.craftableCount);
        assertEquals(0, r.buyCostCopper);
        assertEquals(0, r.estimatedNotesTotal);
        assertEquals(0, r.estimatedNotesCostTotalCopper);
        assertEquals(Map.of(), r.currencyTotal);
    }

    @Test
    void notesFirstConsumedByLaterCraftsAreValuedInTheTotalOnly() {
        // The first craft uses the owned intermediate (no Notes); crafts 2-4 craft it from 30 wallet Notes each.
        int intermediate = 5000;
        Recipe outer = new Recipe(13850, 6000, 1, 400, "Artificer", List.of(ingredient(intermediate, 1)));
        Recipe inner = new Recipe(13851, intermediate, 1, 400, "Artificer", List.of(ingredient(97102, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Map<Integer, PriceQuote> prices = tp(quote(100, 100), null);
        prices.put(6000, quote(5000, 6000));
        CraftResult r = new CraftingPlanner().withWallet(Map.of(NOTE, 90)).evaluateAll(
                List.of(outer, inner, PRR_A), Map.of(intermediate, 1, 97102, 100), Map.of(), prices, OWN_ONLY,
                Set.of(13850, 13851, 15120)).get(13850);
        assertEquals(4, r.craftableCount);
        assertEquals(0, r.estimatedNotesPerCraft);
        assertEquals(90, r.estimatedNotesTotal);
        assertEquals(240, r.estimatedNotesCostTotalCopper);
        assertFalse(r.unpricedCurrency);
    }

    @Test
    void nonCurrencyRecipesAreUnchanged() {
        Recipe plain = new Recipe(13860, OUTPUT, 1, 400, "Artificer", List.of(ingredient(97102, 1)));
        CraftResult r = run(plain, tp(quote(100, 100), null), Map.of(NOTE, 90), Map.of(97102, 3), OWN_ONLY);
        assertEquals(3, r.craftableCount);
        assertEquals("NOT_REQUIRED", r.walletState);
        assertNull(r.noteEstimate);
        assertEquals(0, r.estimatedNotesCostTotalCopper);
    }

    @Test
    void estimateIsSharedAcrossDerivedContexts() {
        PlannerContext ctx = new PlannerContext(Map.of(PRR, List.of(PRR_A, PRR_B)), tp(quote(100, 100), quote(60, 60)),
                OWN_ONLY, Set.of(15120, 15131));
        assertTrue(ctx.researchNoteEstimate() == ctx.withWallet(Map.of()).withWalletIgnored().researchNoteEstimate());
    }
}
