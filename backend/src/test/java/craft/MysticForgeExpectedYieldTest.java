package craft;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expected-yield economics of variable-output Mystic Forge roots. Every expected figure is worked out by hand in
 * the comment next to it (copper), not through a production helper.
 */
class MysticForgeExpectedYieldTest {

    private static final int GOSSAMER = 800_001, LOWER = 800_002, DUST = 800_003, STONE = 800_004, PARENT = 800_005;
    private static final int FORGE_ID = 1_974_501;
    private static final int RID = -FORGE_ID;

    // Gossamer-shaped promotion: 250 lower + 1 self seed + 5 dust + 5 stone; mean 18.92, min 10, max 40.
    private static Recipe promotion(int seedUnits) {
        return Recipe.forgeWithExpectedYield(RID, GOSSAMER, ExpectedYield.of("18.92", 10, 40), List.of(
                new Ingredient(LOWER, 250), new Ingredient(GOSSAMER, seedUnits),
                new Ingredient(DUST, 5), new Ingredient(STONE, 5)));
    }

    // PriceQuote(buyUnit = instant-sell value, sellUnit = purchase price): output sells for 100 and costs 100 to buy.
    private static Map<Integer, PriceQuote> tp() {
        return Map.of(GOSSAMER, new PriceQuote(100, 100), LOWER, new PriceQuote(null, 2), DUST, new PriceQuote(null, 20));
    }

    private static CraftingSettings settings(boolean buying, int budget) {
        return new CraftingSettings(true, buying, budget, false, false, false).withMysticForge(true);
    }

    private static CraftResult run(Recipe r, List<Recipe> all, Map<Integer, Integer> bound, CraftingSettings s) {
        return new CraftingPlanner().evaluateAll(all, Map.of(), bound, tp(), s, Set.of()).get(r.recipeId);
    }

    @Test
    void twoBatchGossamerOracleReusesGuaranteedSeedOnce() {
        Recipe r = promotion(1);
        CraftResult res = run(r, List.of(r), Map.of(STONE, 10), settings(true, 1350));
        assertEquals(2, res.craftableCount, "1350c budget admits both batches, not a third");
        // cash: seed 100 once + 2 x (250*2 + 5*20 = 600) = 1300
        assertEquals(1300, res.buyCostCopper);
        // gross: (2*18.92 - 1 reused seed) = 36.84 units * 100c = 3684c
        assertEquals(3684, res.totalSellValueCopper);
        assertEquals(new BigDecimal("36.84"), res.expectedSellableUnitsTotal);
        assertEquals(1, res.reusedSeedUnits);
        // guaranteed sellable is only the minimum: 2*10 - 1 = 19 units, never the fractional mean
        assertEquals(19, res.guaranteedSellableUnitsTotal);
        // fee 15% of 3684 = 552.6 -> 553 once on the total; margin 3684 - 553 - 1300 = 1831
        assertEquals(1831, res.totalProfitCopper);
        assertTrue(res.estimated);
        assertEquals(new BigDecimal("18.92"), res.expectedYield.mean());
        // per craft expected revenue floor(18.92*100)=1892; first batch also paid the 100c seed:
        // 1892 - fee(1892)=284 - (600+100) = 908
        assertEquals(1892, res.revenueCopper);
        assertEquals(908, res.profitCopper);
    }

    @Test
    void budgetBelowSecondBatchRollsBackToOneBatch() {
        Recipe r = promotion(1);
        // batch 1 costs 700; batch 2 would raise cash to 1300 > 1299
        CraftResult res = run(r, List.of(r), Map.of(STONE, 10), settings(true, 1299));
        assertEquals(1, res.craftableCount);
        assertEquals(700, res.buyCostCopper);
        assertEquals(0, res.reusedSeedUnits);
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, res.blockedReason);
        // 18.92 units -> floor(1892) gross; fee ceil-half-up(283.8)=284; margin 1892-284-700
        assertEquals(1892, res.totalSellValueCopper);
        assertEquals(908, res.totalProfitCopper);
    }

    @Test
    void missingInitialSeedWithBuyingOffIsBlockedNotFree() {
        Recipe r = promotion(1);
        CraftResult res = run(r, List.of(r), Map.of(STONE, 10, LOWER, 250, DUST, 5), settings(false, 0));
        assertEquals(0, res.craftableCount);
        assertEquals(0, res.totalSellValueCopper);
        assertEquals(0, res.totalProfitCopper);
        assertEquals(0, res.reusedSeedUnits);
    }

    @Test
    void ownedInitialSeedIsConsumedAndLaterBatchesReuseGuaranteedOutput() {
        Recipe r = promotion(1);
        Map<Integer, Integer> owned = Map.of(STONE, 10, GOSSAMER, 1);
        CraftResult res = run(r, List.of(r), owned, settings(true, 1200));
        // owned seed is an inventory input (no purchase); 2 batches of 600 = 1200 cash
        assertEquals(2, res.craftableCount);
        assertEquals(1200, res.buyCostCopper);
        assertEquals(1, res.reusedSeedUnits);
    }

    @Test
    void minimumCannotCoverFutureDemandBuysTheShortfall() {
        Recipe r = promotion(15); // consumes 15 seeds per batch, guarantees only 10 back
        Recipe cheap = Recipe.forgeWithExpectedYield(RID, GOSSAMER, ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(GOSSAMER, 15)));
        CraftResult res = run(cheap, List.of(cheap), Map.of(), settings(true, 2000));
        // batch 1: 15 seeds * 100 = 1500; batch 2: 10 reused + 5 bought = 500; batch 3 would add 500 -> 2500 > 2000
        assertEquals(2, res.craftableCount);
        assertEquals(2000, res.buyCostCopper);
        assertEquals(10, res.reusedSeedUnits);
        // gross (2*18.92 - 10) = 27.84 units * 100 = 2784
        assertEquals(2784, res.totalSellValueCopper);
        assertNotNull(r);
    }

    @Test
    void unknownQuoteInventsNoProfit() {
        Recipe r = promotion(1);
        Map<Integer, PriceQuote> noOutputQuote = Map.of(LOWER, new PriceQuote(null, 2), DUST, new PriceQuote(null, 20));
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(r), Map.of(), Map.of(STONE, 10, GOSSAMER, 1),
                noOutputQuote, settings(true, 1200), Set.of()).get(RID);
        assertEquals(0, res.revenueCopper);
        assertEquals(0, res.totalSellValueCopper);
        assertTrue(res.totalProfitCopper <= 0, "no quote means no positive profit");
    }

    @Test
    void parentUsingVariableRouteIsEstimatedAndOnlyGuaranteedMinimumIsStock() {
        Recipe forged = Recipe.forgeWithExpectedYield(RID, GOSSAMER, ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(STONE, 1)));
        Recipe parent = new Recipe(7, PARENT, 1, 0, "", List.of(new Ingredient(GOSSAMER, 11)));
        Map<Integer, PriceQuote> prices = Map.of(PARENT, new PriceQuote(5000, null));
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(forged, parent), Map.of(), Map.of(STONE, 3), prices,
                settings(false, 0), Set.of(7)).get(7);
        // craft 1: two forges (20 guaranteed, 9 left); craft 2: 9 left + one forge (10) = 19 >= 11; 3 stones in all.
        // A mean-based plan (18.92 per forge) would have needed fewer forges; only the guaranteed 10 is stock.
        assertEquals(2, res.craftableCount);
        assertTrue(res.estimated);
        assertNull(res.expectedYield, "the parent itself has a fixed output");
    }

    @Test
    void fixedOrdinaryRecipesStayUnestimated() {
        Recipe fixed = new Recipe(9, PARENT, 1, 0, "", List.of(new Ingredient(STONE, 1)));
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(fixed), Map.of(), Map.of(STONE, 2),
                Map.of(PARENT, new PriceQuote(1000, null)), settings(false, 0), Set.of(9)).get(9);
        assertFalse(res.estimated);
        assertNull(res.expectedYield);
        assertEquals(2, res.craftableCount);
        // 1000 gross * 2 = 2000; fee 150 per craft = 300 -> 1700
        assertEquals(1700, res.totalProfitCopper);
    }

    @Test
    void invalidYieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExpectedYield.of("9.99", 10, 40));
        assertThrows(IllegalArgumentException.class, () -> ExpectedYield.of("41", 10, 40));
        assertThrows(IllegalArgumentException.class, () -> ExpectedYield.of("5", 0, 40));
    }

    @Test
    void fractionalMeanIsFlooredOnceAtTheCopperBoundary() {
        assertEquals(8634, ExpectedYield.floorCopper(new BigDecimal("86.34"), 100));
        assertEquals(1, ExpectedYield.floorCopper(new BigDecimal("0.5"), 3));
    }
}
