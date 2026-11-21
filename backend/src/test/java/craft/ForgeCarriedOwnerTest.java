package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Forge carried stock belongs to its actual owner, independent of the ordinary crafter roster. */
class ForgeCarriedOwnerTest {
    private static final int OUT = 830_000, A = 830_001, B = 830_002;

    private static CraftingSettings settings(boolean buy) {
        return new CraftingSettings(true, buy, buy ? 1_000_000 : 0, false, false, false).withMysticForge(true);
    }

    private static Recipe forge(Ingredient... ing) {
        return new Recipe(-830, OUT, 1, 0, "", List.of(ing), List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static CraftResult run(Recipe r, Map<String, Map<Integer, Integer>> carried, boolean buy, List<CharacterCraftingProfile> roster) {
        return new CraftingPlanner().evaluateAllCoordinated(List.of(r), Map.of(), Map.of(), carried, roster,
                Map.of(OUT, new PriceQuote(5_000, 5_000), A, new PriceQuote(100, 100), B, new PriceQuote(50, 50)),
                settings(buy), Set.of(r.recipeId)).get(r.recipeId);
    }

    private static final List<CharacterCraftingProfile> ROSTER = List.of(new CharacterCraftingProfile("Crafter", Map.of()));

    @Test
    void carriedStockOfCharacterOutsideRosterIsUsedByItsOwnForgeStep() {
        CraftResult res = run(forge(new Ingredient(A, 1)), Map.of("Untrained", Map.of(A, 1)), false, ROSTER);
        assertEquals(1, res.craftableCount);
        assertTrue(res.missingToBuy.isEmpty());
    }

    @Test
    void carriedOwnerIsUsedWithEmptyRoster() {
        CraftResult res = run(forge(new Ingredient(A, 1)), Map.of("Untrained", Map.of(A, 1)), false, List.of());
        assertEquals(1, res.craftableCount);
    }

    @Test
    void carriedOwnerAvoidsBuyingWhileSecondIngredientIsBoughtOnSharedBudget() {
        CraftResult res = run(forge(new Ingredient(A, 1), new Ingredient(B, 1)),
                Map.of("Alt", Map.of(A, 1)), true, ROSTER);
        assertTrue(res.craftableCount > 1, "buying lets the plan repeat");
        assertFalse(res.missingToBuyOne.containsKey(A), "the owner's carried A must not be bought again on the first craft");
        assertEquals(1, res.missingToBuyOne.get(B));
        assertEquals(res.craftableCount - 1, res.missingToBuy.get(A), "only repeats buy A; the one carried unit is used");
    }

    @Test
    void carriedStockOfDifferentOwnersIsNeverPooled() {
        Map<String, Map<Integer, Integer>> carried = Map.of("One", Map.of(A, 1), "Two", Map.of(A, 1));
        CraftResult buying = run(forge(new Ingredient(A, 2)), carried, true, ROSTER);
        assertEquals(1, buying.missingToBuyOne.get(A), "one unit comes from one owner, the other is bought");
        CraftResult noBuying = run(forge(new Ingredient(A, 2)), carried, false, ROSTER);
        assertEquals(0, noBuying.craftableCount);
    }

    @Test
    void bankBoundStockOfAnyOwnerIsSharedByForge() {
        CraftResult res = run(forge(new Ingredient(A, 1)),
                Map.of(PlanState.bankOwnerKey("Nobody"), Map.of(A, 1)), false, ROSTER);
        assertEquals(1, res.craftableCount);
    }

    @Test
    void missingSeedWithBuyingOffPublishesUnknownProfitNotOutputRevenue() {
        CraftResult res = run(forge(new Ingredient(A, 1)), Map.of(), false, ROSTER);
        assertEquals(0, res.craftableCount);
        assertTrue(res.profitUnknown);
        assertEquals(5_000, res.revenueCopper, "the output quote stays");
        CraftResult ok = run(forge(new Ingredient(A, 1)), Map.of("Alt", Map.of(A, 1)), false, ROSTER);
        assertFalse(ok.profitUnknown);
    }

    @Test
    void workCounterIsSharedAcrossCopiesAndNeverRefunded() {
        PlanState parent = new PlanState(Map.of());
        parent.resetWork(3);
        assertTrue(parent.chargeWork());
        PlanState first = new PlanState(parent);
        PlanState second = new PlanState(parent);
        assertTrue(first.chargeWork());
        assertTrue(second.chargeWork());
        assertEquals(3, parent.workUsed());
        int mark = first.mark();
        first.rollbackTo(mark);
        assertFalse(parent.chargeWork(), "spending in two successive copies charged the one root counter");
        assertTrue(first.isWorkExhausted());
        parent.resetWork(3);
        assertFalse(parent.isWorkExhausted());
        assertTrue(first.isWorkExhausted(), "a new root's fresh authority does not revive the old root's copies");
    }
}
