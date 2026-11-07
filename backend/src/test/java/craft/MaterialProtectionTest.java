package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Material Protection (STORY-MAT-001): protected item IDs lose all pre-existing owned copies. */
class MaterialProtectionTest {

    private static final int FINAL = 1, MID = 2, LOW = 3, RAW = 4;

    private static CraftingSettings settings(boolean buy, int budget, boolean protect, Integer... ids) {
        return new CraftingSettings(true, buy, budget, false, false, true, protect, List.of(ids));
    }

    private static final List<CharacterCraftingProfile> ROSTER = List.of(
            CraftTestFixtures.profile("Alice", "Artificer", 50),
            CraftTestFixtures.profile("Bob", "Artificer", 50));

    /** FINAL <- 1 MID; MID <- 1 LOW; LOW <- 1 RAW. RAW is only needed at depth three. */
    private static List<Recipe> chain() {
        return List.of(
                CraftTestFixtures.recipe(1, FINAL, List.of(CraftTestFixtures.ingredient(MID, 1))),
                CraftTestFixtures.recipe(2, MID, List.of(CraftTestFixtures.ingredient(LOW, 1))),
                CraftTestFixtures.recipe(3, LOW, List.of(CraftTestFixtures.ingredient(RAW, 1))));
    }

    private static Map<String, Map<Integer, Integer>> charBound() {
        return Map.of("Alice", Map.of(RAW, 1), "Bob", Map.of(RAW, 1));
    }

    private static CraftResult run(CraftingSettings s, Map<Integer, PriceQuote> tp,
                                   Map<Integer, Integer> sellable, Map<Integer, Integer> accountBound,
                                   Map<String, Map<Integer, Integer>> characterBound) {
        return new CraftingPlanner().evaluateAllCoordinated(chain(), sellable, accountBound,
                characterBound, ROSTER, tp, s, Set.of(1, 2, 3)).get(1);
    }

    @Test
    void unprotectedBaselineConsumesEveryOwnedPoolAtDepth() {
        CraftResult r = run(settings(false, 0, false), Map.of(), Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        assertEquals(5, r.craftableCount);
    }

    @Test
    void protectedItemIsUnavailableInSellableAccountBoundAndCharacterBoundPoolsAtEveryDepth() {
        CraftResult r = run(settings(false, 0, true, RAW), Map.of(), Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        assertEquals(0, r.craftableCount);
        assertEquals(0, r.totalMatsSellValueCopper);
        assertTrue(r.missingToBuy.isEmpty() || r.missingToBuy.values().stream().allMatch(v -> v >= 0));
    }

    @Test
    void protectionDoesNotForbidBuyingAReplacementWhenBuyingIsAllowed() {
        Map<Integer, PriceQuote> tp = Map.of(RAW, CraftTestFixtures.quote(10, 10));
        CraftResult r = run(settings(true, 100, true, RAW), tp, Map.of(RAW, 50), Map.of(), Map.of());
        assertTrue(r.craftableCount > 0);
        assertEquals(10, r.craftableCount);               // budget 100 / unit 10; the 50 owned are not used
        assertEquals(100, r.buyCostCopper);
        assertEquals(0, r.totalMatsSellValueCopper, "protected copies create no owned economic value");
    }

    @Test
    void insufficientBudgetOrMissingPriceStillBlocksTheReplacement() {
        Map<Integer, PriceQuote> tp = Map.of(RAW, CraftTestFixtures.quote(10, 10));
        assertEquals(0, run(settings(true, 5, true, RAW), tp, Map.of(RAW, 50), Map.of(), Map.of()).craftableCount);
        assertEquals(0, run(settings(true, 100, true, RAW), Map.of(), Map.of(RAW, 50), Map.of(), Map.of())
                .craftableCount);
    }

    @Test
    void protectedIntermediateCanStillBeCraftedAndItsProtectedChildStaysProtected() {
        // 5 owned MID are protected, RAW is not: MID is crafted from RAW instead of consumed.
        CraftResult mid = run(settings(false, 0, true, MID), Map.of(), Map.of(MID, 5, RAW, 2), Map.of(), Map.of());
        assertEquals(2, mid.craftableCount, "only the 2 RAW can feed crafting; the 5 MID stay untouched");
        // Protecting both: nothing owned of either is usable.
        CraftResult both = run(settings(false, 0, true, MID, RAW), Map.of(), Map.of(MID, 5, RAW, 2),
                Map.of(), Map.of());
        assertEquals(0, both.craftableCount);
    }

    @Test
    void surplusProducedInThePlanIsStillReusable() {
        // MID is crafted two at a time; the surplus unit is reused by the second FINAL craft.
        List<Recipe> recipes = List.of(
                CraftTestFixtures.recipe(1, FINAL, List.of(CraftTestFixtures.ingredient(MID, 1))),
                CraftTestFixtures.recipe(2, MID, 2, List.of(CraftTestFixtures.ingredient(RAW, 1))));
        CraftResult r = new CraftingPlanner().evaluateAllCoordinated(recipes, Map.of(RAW, 1), Map.of(), Map.of(),
                ROSTER, Map.of(), settings(false, 0, true, MID), Set.of(1, 2)).get(1);
        assertEquals(2, r.craftableCount);
    }

    @Test
    void disabledWithSelectionAndEnabledWithEmptySelectionPreserveOrdinaryBehavior() {
        CraftResult base = run(settings(false, 0, false), Map.of(), Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        CraftResult disabled = run(settings(false, 0, false, RAW), Map.of(), Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        CraftResult empty = run(settings(false, 0, true), Map.of(), Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        assertEquals(base.craftableCount, disabled.craftableCount);
        assertEquals(base.craftableCount, empty.craftableCount);
        assertEquals(base.totalMatsSellValueCopper, disabled.totalMatsSellValueCopper);
        assertEquals(base.totalMatsSellValueCopper, empty.totalMatsSellValueCopper);
    }

    @Test
    void protectedThenUnprotectedCalculationsOverTheSameInventoryReproduceTheCleanResult() {
        Map<Integer, Integer> sellable = Map.of(RAW, 2);
        Map<Integer, Integer> bound = Map.of(RAW, 1);
        Map<String, Map<Integer, Integer>> chars = charBound();
        CraftResult before = run(settings(false, 0, false), Map.of(), sellable, bound, chars);
        run(settings(false, 0, true, RAW), Map.of(), sellable, bound, chars);
        CraftResult after = run(settings(false, 0, false), Map.of(), sellable, bound, chars);
        assertEquals(before.craftableCount, after.craftableCount);
        assertEquals(Map.of(RAW, 2), sellable);
        assertEquals(Map.of(RAW, 1), bound);
        assertEquals(Map.of(RAW, 1), chars.get("Alice"));
    }

    @Test
    void concurrentCalculationsWithDifferentSelectionsDoNotContaminateEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            java.util.ArrayList<Callable<Boolean>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                boolean protect = i % 2 == 0;
                jobs.add(() -> {
                    CraftResult r = run(settings(false, 0, protect, RAW), Map.of(), Map.of(RAW, 2),
                            Map.of(RAW, 1), charBound());
                    return r.craftableCount == (protect ? 0 : 5);
                });
            }
            for (Future<Boolean> f : pool.invokeAll(jobs)) assertTrue(f.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settingsMembershipIsCanonical() {
        CraftingSettings s = settings(false, 0, true, 7, 7, 3);
        assertEquals(Set.of(3, 7), s.protectedItemIds);
        assertTrue(s.isProtected(7));
        assertFalse(s.isProtected(8));
        assertFalse(settings(false, 0, false, 7).isProtected(7));
        assertTrue(s.withBuyingDisabled().isProtected(3), "the no-buy phase keeps protection");
    }

    @Test
    void protectedDirectResolutionConsumesNoOwnedCopyInAnyPool() {
        CraftingSettings s = settings(false, 0, true, RAW);
        PlannerContext ctx = CraftTestFixtures.coordinatedContext(Map.of(), Map.of(), s, Set.of(), ROSTER);
        PlanState state = CraftTestFixtures.coordinatedState(Map.of(RAW, 2), Map.of(RAW, 1), charBound());
        ResolvedNeed need = new CraftingResolver().resolveNeed(RAW, 3, ctx, state, true);
        assertEquals(0, need.getQtyFromInventory());
        assertEquals(0, need.getOpportunityCostCopper());
        assertFalse(need.isFullySatisfied());
    }
}
