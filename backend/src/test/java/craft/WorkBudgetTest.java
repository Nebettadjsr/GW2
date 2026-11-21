package craft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The per-root speculative work budget: bounded counts (never wall-clock), truthful limited outcome. */
class WorkBudgetTest {
    private static final int TOP = 910_000, MID = 910_001, LEAF = 910_002;

    private static CraftingSettings own() {
        return new CraftingSettings(true, false, 0, false, false, false);
    }

    private static CraftingSettings buying() {
        return new CraftingSettings(true, true, 0, false, false, false);
    }

    /** TOP(1) <- MID(1) <- LEAF(1): two craft steps per execution. */
    private static List<Recipe> chain() {
        return List.of(new Recipe(1, TOP, 1, 0, "", List.of(new Ingredient(MID, 1))),
                new Recipe(2, MID, 1, 0, "", List.of(new Ingredient(LEAF, 1))));
    }

    private static final Map<Integer, PriceQuote> TP = Map.of(TOP, new PriceQuote(900, 1000), LEAF, new PriceQuote(5, 10));

    @Test
    void unlimitedAndAmpleBudgetAreIdentical() {
        CraftResult exact = new CraftingPlanner().evaluateAll(chain(), Map.of(LEAF, 10), TP, own(), Set.of(1, 2)).get(1);
        CraftResult ample = new CraftingPlanner().withWorkLimit(1_000)
                .evaluateAll(chain(), Map.of(LEAF, 10), TP, own(), Set.of(1, 2)).get(1);
        assertEquals(10, exact.craftableCount);
        assertNotEquals(BlockedReason.WORK_LIMIT, exact.blockedReason);
        assertEquals(exact.craftableCount, ample.craftableCount);
        assertEquals(exact.totalProfitCopper, ample.totalProfitCopper);
        assertNotEquals(BlockedReason.WORK_LIMIT, ample.blockedReason);
    }

    @Test
    void exhaustionKeepsOnlyFullyAcceptedPrefixWithExactFigures() {
        // 2 attempts per execution; budget 5 accepts two executions, the third is refused part-way.
        CraftResult exact = new CraftingPlanner().evaluateAll(chain(), Map.of(LEAF, 10), TP, own(), Set.of(1, 2)).get(1);
        CraftResult limited = new CraftingPlanner().withWorkLimit(5)
                .evaluateAll(chain(), Map.of(LEAF, 10), TP, own(), Set.of(1, 2)).get(1);
        assertEquals(BlockedReason.WORK_LIMIT, limited.blockedReason);
        assertEquals(2, limited.craftableCount);
        assertEquals(exact.profitCopper, limited.profitCopper, "per-craft economics of the accepted prefix are exact");
        assertEquals(2 * exact.profitCopper, limited.totalProfitCopper);
    }

    @Test
    void buyingAFallbackCannotLaunderAnExhaustedPlanIntoAnExactResult() {
        // Budget 1: only TOP is attempted; MID's craft is refused and LEAF-less buying cannot hide that.
        CraftResult limited = new CraftingPlanner().withWorkLimit(1)
                .evaluateAll(chain(), Map.of(), Map.of(TOP, new PriceQuote(900, 1000), MID, new PriceQuote(5, 10)),
                        buying(), Set.of(1, 2)).get(1);
        assertEquals(BlockedReason.WORK_LIMIT, limited.blockedReason);
        assertEquals(0, limited.craftableCount);
    }

    @Test
    void zeroAcceptedCraftsKeepsOutputQuoteButNoInventedProfitAtTheDomainLevel() {
        CraftResult limited = new CraftingPlanner().withWorkLimit(0)
                .evaluateAll(chain(), Map.of(LEAF, 10), TP, own(), Set.of(1, 2)).get(1);
        assertEquals(BlockedReason.WORK_LIMIT, limited.blockedReason);
        assertEquals(0, limited.craftableCount);
        assertEquals(0, limited.totalProfitCopper);
    }

    @Test
    void budgetIsNotRefundedByRollbackAndPersistsOnCopies() {
        PlanState s = new PlanState(Map.of());
        s.resetWork(2);
        int mark = s.mark();
        assertTrue(s.chargeWork());
        assertTrue(s.chargeWork());
        s.rollbackTo(mark);
        assertEquals(2, s.workUsed());
        assertFalse(s.chargeWork());
        s.rollbackTo(0);
        assertTrue(s.isWorkExhausted());
        assertTrue(new PlanState(s).isWorkExhausted());
        s.resetWork(2);
        assertFalse(s.isWorkExhausted(), "a new root on a reused worker state starts fresh");
    }

    /** Ordinary + Forge nested branching with 2 eligible characters: attempts are bounded, never unbounded. */
    @Test
    void nestedBranchingWithManyCharactersStopsAtTheBudgetAndStaysInterruptible() {
        int levels = 9;
        List<Recipe> rs = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            int out = 920_000 + i, in = 920_000 + i + 1;
            // Two alternative ordinary recipes per level (each needs the next level twice) multiply routes.
            rs.add(new Recipe(10 + i, out, 1, 0, "Chef", List.of(new Ingredient(in, 1), new Ingredient(in, 1))));
            rs.add(Recipe.forgeWithExpectedYield(-10 - i, out, ExpectedYield.of("2.5", 2, 3), List.of(new Ingredient(in, 1))));
        }
        List<CharacterCraftingProfile> roster = List.of(new CharacterCraftingProfile("A", Map.of("Chef", 500)),
                new CharacterCraftingProfile("B", Map.of("Chef", 500)));
        CraftingSettings s = new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
        Set<Integer> allowed = new java.util.HashSet<>();
        rs.forEach(r -> allowed.add(r.recipeId));
        int top = rs.getFirst().recipeId;
        CraftResult limited = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                new CraftingPlanner().withWorkLimit(500).evaluateAllCoordinated(rs, Map.of(920_000 + levels, 1_000), Map.of(), Map.of(), roster,
                        Map.of(920_000, new PriceQuote(1, 100)), s, Set.of(top)).get(top));
        assertEquals(BlockedReason.WORK_LIMIT, limited.blockedReason);
        assertTrue(limited.craftableCount < 250, "only the fully accepted prefix survives the budget");
    }

    @Test
    void mixedVendorAndTradingPostPurchaseCostsOnlyPriceTheTpUnitsAtTheQuote() {
        // 10 missing: 4 vendor-bought for 400c cash, 6 from the TP at 30c each => 400 + 180.
        Map<Integer, MaterialPurchaseCost> c = CraftingPlanner.purchaseCosts(Map.of(7, 10), Map.of(7, new int[]{4, 400}),
                Map.of(7, new PriceQuote(25, 30)), own());
        assertEquals(580, c.get(7).totalPriceCopper());
        // Wallet-currency-only vendor units add no cash: 6 TP units only.
        Map<Integer, MaterialPurchaseCost> w = CraftingPlanner.purchaseCosts(Map.of(7, 10), Map.of(7, new int[]{4, 0}),
                Map.of(7, new PriceQuote(25, 30)), own());
        assertEquals(180, w.get(7).totalPriceCopper());
        // Entirely vendor-bought keeps the exact vendor cash, independent of any TP quote.
        Map<Integer, MaterialPurchaseCost> v = CraftingPlanner.purchaseCosts(Map.of(7, 4), Map.of(7, new int[]{4, 400}),
                Map.of(7, new PriceQuote(25, 30)), own());
        assertEquals(400, v.get(7).totalPriceCopper());
        // No TP quote for the remaining units: unknown, never the vendor cash alone.
        Map<Integer, MaterialPurchaseCost> n = CraftingPlanner.purchaseCosts(Map.of(7, 10), Map.of(7, new int[]{4, 400}),
                Map.of(), own());
        assertNull(n.get(7).totalPriceCopper());
    }
}
