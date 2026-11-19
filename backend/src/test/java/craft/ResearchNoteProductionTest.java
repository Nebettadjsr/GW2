package craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #372: missing Research Notes (currency 61) are sourced by crafting Peer Reviewed Research (item 104934), which
 * immediately credits exactly 150 Notes. Both production recipes (15120, 15131) take part in normal recipe selection.
 */
class ResearchNoteProductionTest {

    private static final int NOTE = CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID;
    private static final int PRR = CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID;
    private static final int OUTPUT = 97487;

    private static final Recipe CONSUMER = new Recipe(13839, OUTPUT, 1, 400, "Artificer",
            List.of(ingredient(97102, 4)), List.of(new CurrencyRequirement(NOTE, 30)));
    private static final Recipe PRR_A = new Recipe(15120, PRR, 1, 0, "Artificer", List.of(ingredient(1001, 4)));
    private static final Recipe PRR_B = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(ingredient(2001, 4)));
    private static final Set<Integer> ALLOWED = Set.of(13839, 15120, 15131);
    private static final Map<Integer, PriceQuote> TP = Map.of(OUTPUT, quote(5000, 6000),
            1001, quote(100, 100), 2001, quote(60, 60));
    private static final CraftingSettings OWN_ONLY = new CraftingSettings(true, false, 0, false, false, true);
    private static final CraftingSettings BUYING = new CraftingSettings(true, true, 0, false, false, true);

    private static CraftResult run(List<Recipe> recipes, Map<Integer, Integer> wallet, Map<Integer, Integer> stock,
                                   CraftingSettings settings) {
        return new CraftingPlanner().withWallet(wallet)
                .evaluateAll(recipes, stock, Map.of(), TP, settings, ALLOWED).get(13839);
    }

    private static Map<Integer, Integer> stock(int jade, int prrMaterialsA) {
        Map<Integer, Integer> stock = new HashMap<>();
        stock.put(97102, jade);
        if (prrMaterialsA > 0) stock.put(1001, prrMaterialsA);
        return stock;
    }

    @Test
    void knownEmptyWalletIsCoveredByBatchesOf150AndSurplusServesLaterCrafts() {
        // Two batches of materials (8 units of 1001): 300 Notes = 10 crafts, though only 1 batch fits 5 crafts.
        CraftResult r = run(List.of(CONSUMER, PRR_A), Map.of(), stock(100, 8), OWN_ONLY);
        assertEquals(10, r.craftableCount);
        assertEquals(Map.of(NOTE, 30), r.currencyPerCraft);
        assertEquals(Map.of(NOTE, 300), r.currencyTotal);
        assertEquals("KNOWN", r.walletState);
        assertFalse(r.unpricedCurrency, "every consumed Note was produced by charged batches");
    }

    @Test
    void thirdBatchWithoutMaterialsStopsTheCraftsAtTheCreditedNotes() {
        CraftResult r = run(List.of(CONSUMER, PRR_A), Map.of(), stock(100, 4), OWN_ONLY);
        assertEquals(5, r.craftableCount, "one batch = 150 Notes = 5 crafts");
        assertEquals(Map.of(NOTE, 150), r.currencyTotal);
    }

    @Test
    void unknownWalletIsNotRelianceButAVerifiedProductionPathStillCoversTheRequirement() {
        CraftResult r = run(List.of(CONSUMER, PRR_A), null, stock(100, 4), OWN_ONLY);
        assertEquals(5, r.craftableCount);
        assertEquals("UNKNOWN", r.walletState);
        assertFalse(r.unpricedCurrency);
    }

    @Test
    void unknownWalletWithoutAFeasiblePathStaysBlockedAndUnknown() {
        CraftResult none = run(List.of(CONSUMER), null, stock(100, 8), OWN_ONLY);
        assertEquals(0, none.craftableCount);
        assertEquals(BlockedReason.WALLET_UNAVAILABLE, none.blockedReason);
        assertTrue(none.unpricedCurrency);

        CraftResult noMaterials = run(List.of(CONSUMER, PRR_A), null, stock(100, 0), OWN_ONLY);
        assertEquals(0, noMaterials.craftableCount);
        assertEquals(BlockedReason.WALLET_UNAVAILABLE, noMaterials.blockedReason);
    }

    @Test
    void ownedWalletNotesAreUsedFirstAndOnlyTheShortfallIsCrafted() {
        // 45 owned + 1 batch (150) = 195 Notes = 6 crafts (one batch of materials only).
        CraftResult r = run(List.of(CONSUMER, PRR_A), Map.of(NOTE, 45), stock(100, 4), OWN_ONLY);
        assertEquals(6, r.craftableCount);
        assertEquals(Map.of(NOTE, 180), r.currencyTotal);
        // Changed from "owned wallet Notes stay unpriced": the approved Research Note estimate now values the 45
        // wallet Notes (recipe 15120: 4 x 100c per 150 Notes) at ceil(45 * 400 / 150) = 120c, rounded once.
        assertFalse(r.unpricedCurrency, "wallet Notes are valued by the estimate");
        assertEquals(45, r.estimatedNotesTotal);
        assertEquals(120, r.estimatedNotesCostTotalCopper);

        // Wallet already covers everything: no production, no materials needed.
        CraftResult owned = run(List.of(CONSUMER, PRR_A), Map.of(NOTE, 90), stock(100, 0), OWN_ONLY);
        assertEquals(3, owned.craftableCount);
        assertFalse(owned.unpricedCurrency);
        assertEquals(90, owned.estimatedNotesTotal);
        assertEquals(240, owned.estimatedNotesCostTotalCopper);
    }

    @Test
    void craftedNotesCostTheFullBatchMaterialCashChargedOnce() {
        // Buying allowed, 2 Jade crafts (8 units of 97102), wallet empty: one 400c batch (4 x 100c), not 150/30 shares.
        CraftResult r = run(List.of(CONSUMER, PRR_A), Map.of(), stock(8, 0), BUYING);
        assertEquals(2, r.craftableCount);
        assertEquals(400, r.buyCostCopper, "one batch charged once for both crafts");
        assertEquals(400, r.missingToBuyOne.getOrDefault(1001, 0) * 100);
        assertFalse(r.unpricedCurrency);
        int fee = (int) tradingpost.TradingPostFeePolicy.feeOn(r.revenueCopper);
        assertEquals(r.revenueCopper - fee - 400, r.profitCopper, "the first craft carries the whole batch");
        assertEquals(2 * (r.revenueCopper - fee) - 400, r.totalProfitCopper);
    }

    @Test
    void theCheaperProductionVariantIsChosen() {
        CraftResult r = run(List.of(CONSUMER, PRR_A, PRR_B), Map.of(), stock(4, 0), BUYING);
        assertEquals(1, r.craftableCount);
        assertEquals(4 * 60, r.buyCostCopper, "recipe 15131 (60c ingredients) beats 15120 (100c)");
        assertEquals(4, r.missingToBuyOne.get(2001));
        assertFalse(r.missingToBuyOne.containsKey(1001));
    }

    @Test
    void eitherVariantAloneIsUsable() {
        assertEquals(5, run(List.of(CONSUMER, PRR_B), Map.of(), Map.of(97102, 100, 2001, 4), OWN_ONLY).craftableCount);
        assertEquals(5, run(List.of(CONSUMER, PRR_A), Map.of(), Map.of(97102, 100, 1001, 4), OWN_ONLY).craftableCount);
    }

    @Test
    void aBudgetThatCannotPayTheBatchRollsBackNotesCostsAndSpending() {
        CraftingSettings budget = new CraftingSettings(true, true, 300, false, false, true);
        CraftResult r = run(List.of(CONSUMER, PRR_A), Map.of(), stock(8, 0), budget);
        assertEquals(0, r.craftableCount);
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, r.blockedReason);
        assertEquals(0, r.buyCostCopper);
        assertEquals(Map.of(), r.currencyTotal);
    }

    @Test
    void anUnaffordableBatchLeavesNoCreditBehindForAnAlternativeRecipe() {
        // Alternative consumer recipes for the same output: the first needs a batch that cannot be built (no
        // materials, buying off) and must leave neither credit nor demand for the second, wallet-paid one.
        Recipe hungry = new Recipe(13840, OUTPUT, 1, 400, "Artificer", List.of(ingredient(97102, 4)),
                List.of(new CurrencyRequirement(NOTE, 500)));
        CraftResult r = new CraftingPlanner().withWallet(Map.of(NOTE, 30)).evaluateAll(
                List.of(CONSUMER, hungry, PRR_A), stock(100, 0), Map.of(), TP, OWN_ONLY, Set.of(13839, 13840, 15120))
                .get(13839);
        assertEquals(1, r.craftableCount);
        assertEquals(Map.of(NOTE, 30), r.currencyTotal);
    }

    @Test
    void currencyIdIsNeverTreatedAsTheProducerItem() {
        // Owning 1000 of item 61 must not pay currency 61 and an owned Peer Reviewed Research item credits 150.
        Map<Integer, Integer> stock = stock(100, 0);
        stock.put(61, 1000);
        assertEquals(0, run(List.of(CONSUMER, PRR_A), Map.of(), stock, OWN_ONLY).craftableCount);
        stock.remove(61);
        stock.put(PRR, 1);
        assertEquals(5, run(List.of(CONSUMER, PRR_A), Map.of(), stock, OWN_ONLY).craftableCount);
    }
}
