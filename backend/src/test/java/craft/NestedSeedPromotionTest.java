package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Sequential guaranteed-minimum seed reuse for NESTED variable Forge routes (independent hand-computed figures). */
class NestedSeedPromotionTest {

    private static final int X = 910_001, OTHER = 910_002, PARENT = 910_003;
    private static final int XR = -910_001, PR = -910_003;

    private static Recipe promotion(int min, String mean, int max, int seed) {
        return Recipe.forgeWithExpectedYield(XR, X, ExpectedYield.of(mean, min, max),
                List.of(new Ingredient(X, seed), new Ingredient(OTHER, 1)));
    }

    private static PlannerContext ctx(Recipe forge, boolean buying, Map<Integer, PriceQuote> tp) {
        CraftingSettings s = new CraftingSettings(true, buying, 0, false, false, false).withMysticForge(true);
        return new PlannerContext(Map.of(X, List.of(forge)), tp, s, Set.of(XR));
    }

    @Test
    void oneOwnedSeedGivesNineteenNetFromTwoBatchesWithoutBuying() {
        PlannerContext c = ctx(promotion(10, "18.92", 40, 1), false, Map.of());
        PlanState st = new PlanState(Map.of(X, 1, OTHER, 10));
        ResolvedNeed need = new CraftingResolver().resolveNeed(X, 19, c, st, true);
        assertTrue(need.isFullySatisfied(), "blocked: " + need.getBlockedReason());
        assertEquals(0, need.getQtyBought());
        // 2 batches produce 20, one of them re-consumed as the second batch's seed: 19 net, nothing left over.
        ResolvedNeed craft = find(need);
        assertEquals(2, craft.getYieldBatches());
        assertEquals(20, craft.getYieldProducedGross());
        assertEquals(1, craft.getYieldReusedSeed());
        assertEquals(19, craft.getYieldProducedNet());
        assertEquals(0, st.surplusOf(X));
        assertTrue(need.usesExpectedYield());
    }

    @Test
    void needingTwentyRunsThreeBatchesAndLeavesEightSurplus() {
        PlannerContext c = ctx(promotion(10, "18.92", 40, 1), false, Map.of());
        PlanState st = new PlanState(Map.of(X, 1, OTHER, 10));
        ResolvedNeed need = new CraftingResolver().resolveNeed(X, 20, c, st, true);
        assertTrue(need.isFullySatisfied());
        ResolvedNeed craft = find(need);
        assertEquals(3, craft.getYieldBatches());
        assertEquals(30, craft.getYieldProducedGross());
        assertEquals(2, craft.getYieldReusedSeed());
        assertEquals(28, craft.getYieldProducedNet());
        assertEquals(8, st.surplusOf(X), "28 net - 20 requested; fractional mean is never stock");
        // only the 3 batches' other ingredient is consumed
        assertEquals(3, st.consumedSellableQuantity(OTHER));
    }

    @Test
    void noSeedAndNoBuyingBlocksInsteadOfInventingOne() {
        PlannerContext c = ctx(promotion(10, "18.92", 40, 1), false, Map.of());
        PlanState st = new PlanState(Map.of(OTHER, 10));
        ResolvedNeed need = new CraftingResolver().resolveNeed(X, 19, c, st, true);
        assertFalse(need.isFullySatisfied());
        assertEquals(0, st.surplusOf(X));
    }

    @Test
    void boughtSeedIsChargedOnceNotPerBatch() {
        PlannerContext c = ctx(promotion(10, "18.92", 40, 1), true, Map.of(X, new PriceQuote(100, 100)));
        PlanState st = new PlanState(Map.of(OTHER, 10));
        ResolvedNeed need = new CraftingResolver().resolveNeed(X, 28, c, st, false);
        assertTrue(need.isFullySatisfied(), "blocked: " + need.getBlockedReason());
        assertEquals(3, find(need).getYieldBatches());
        assertEquals(100, need.getBuyCostCopper(), "one external seed; later batches reuse produced units");
    }

    @Test
    void minimumNotAboveSeedGainsNothing() {
        // min 1 consuming 1 seed: no guaranteed gain, so it must never promise net units or infinite crafts.
        PlannerContext c = ctx(promotion(1, "1.5", 3, 1), false, Map.of());
        PlanState st = new PlanState(Map.of(X, 1, OTHER, 10));
        ResolvedNeed need = new CraftingResolver().resolveNeed(X, 2, c, st, true);
        assertFalse(need.isFullySatisfied());
    }

    @Test
    void rootAggregateEstimateReflectsAcceptedCraftsOnly() {
        // Ordinary root: crafts use owned stock first and a Forge step only later -> estimated; fully-owned-only -> not.
        Recipe forge = Recipe.forgeWithExpectedYield(XR, OTHER, ExpectedYield.of("2.5", 2, 3),
                List.of(new Ingredient(910_010, 1)));
        Recipe root = new Recipe(PR, PARENT, 1, 0, "", List.of(new Ingredient(OTHER, 2)), List.of());
        Map<Integer, PriceQuote> tp = Map.of(PARENT, new PriceQuote(1000, 1000));
        CraftingSettings s = new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
        // OTHER owned x2 covers craft 1; craft 2 needs the Forge step, fed by 910010.
        CraftResult mixed = new CraftingPlanner().evaluateAll(List.of(root, forge), Map.of(OTHER, 2, 910_010, 1),
                Map.of(), tp, s, Set.of(PR, XR)).get(PR);
        assertEquals(2, mixed.craftableCount);
        assertTrue(mixed.estimated, "a later accepted craft used the variable route");
        // Only owned stock: the rejected Forge attempt must not mark the accepted plan estimated.
        CraftResult owned = new CraftingPlanner().evaluateAll(List.of(root, forge), Map.of(OTHER, 2),
                Map.of(), tp, s, Set.of(PR, XR)).get(PR);
        assertEquals(1, owned.craftableCount);
        assertFalse(owned.estimated);
    }

    private static ResolvedNeed find(ResolvedNeed n) {
        if (n.getYieldBatches() > 0) return n;
        for (ResolvedNeed c : n.getChildren()) {
            ResolvedNeed f = find(c);
            if (f != null) return f;
        }
        return null;
    }
}
