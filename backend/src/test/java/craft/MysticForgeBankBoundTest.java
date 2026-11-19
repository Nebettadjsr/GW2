package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bank-resident soulbound stock (Wiki: Bank items usable in the Forge since 2022-10-18) vs carried stock. */
class MysticForgeBankBoundTest {

    private static final int OUT = 810_001, IN = 810_002, ORD_OUT = 810_003;
    private static final PriceQuote OUT_PRICE = new PriceQuote(1000, null);

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
    }

    private static Recipe forge(int id, int need) {
        return new Recipe(id, OUT, 1, 0, "", List.of(new Ingredient(IN, need)), List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static CharacterCraftingProfile untrained(String n) {
        return new CharacterCraftingProfile(n, Map.of());
    }

    private static CraftResult run(Recipe r, Map<String, Map<Integer, Integer>> bound, List<CharacterCraftingProfile> roster,
                                   Map<Integer, Integer> shared) {
        return new CraftingPlanner().evaluateAllCoordinated(List.of(r), shared, Map.of(), bound, roster,
                Map.of(OUT, OUT_PRICE), settings(), Set.of(r.recipeId)).get(r.recipeId);
    }

    @Test
    void twoOwnersBankBoundInputsCombineInOneForgeStep() {
        CraftResult res = run(forge(-1, 2), Map.of(PlanState.bankOwnerKey("A"), Map.of(IN, 1),
                PlanState.bankOwnerKey("B"), Map.of(IN, 1)), List.of(untrained("A"), untrained("B")), Map.of());
        assertEquals(1, res.craftableCount);
        assertTrue(res.missingToBuy.isEmpty());
    }

    @Test
    void bankBoundOfOffRosterOrUnknownOwnerAndEmptyRosterIsForgeUsable() {
        CraftResult res = run(forge(-2, 2), Map.of(PlanState.bankOwnerKey("Gone"), Map.of(IN, 1),
                PlanState.bankOwnerKey(null), Map.of(IN, 1)), List.of(), Map.of());
        assertEquals(1, res.craftableCount);
    }

    @Test
    void identicalCarriedInputsOfTwoOwnersStillCannotPool() {
        CraftResult res = run(forge(-3, 2), Map.of("A", Map.of(IN, 1), "B", Map.of(IN, 1)),
                List.of(untrained("A"), untrained("B")), Map.of());
        assertEquals(0, res.craftableCount);
    }

    @Test
    void carriedOfOneOwnerPlusAnyBankBoundCombine() {
        CraftResult res = run(forge(-4, 2), Map.of("A", Map.of(IN, 1), PlanState.bankOwnerKey("B"), Map.of(IN, 1)),
                List.of(untrained("A"), untrained("B")), Map.of());
        assertEquals(1, res.craftableCount);
    }

    @Test
    void ordinaryCraftingCannotSpendAnotherOwnersBankBoundItem() {
        Recipe ordinary = new Recipe(-5, ORD_OUT, 1, 0, "Weaponsmith", List.of(new Ingredient(IN, 1)), List.of(), RecipeSource.ORDINARY);
        CharacterCraftingProfile smith = new CharacterCraftingProfile("Smith", Map.of("Weaponsmith", 500));
        CraftingPlanner planner = new CraftingPlanner();
        Map<Integer, PriceQuote> tp = Map.of(ORD_OUT, OUT_PRICE);
        CraftResult other = planner.evaluateAllCoordinated(List.of(ordinary), Map.of(), Map.of(),
                Map.of(PlanState.bankOwnerKey("Other"), Map.of(IN, 1)), List.of(smith), tp, settings(), Set.of(-5)).get(-5);
        assertEquals(0, other.craftableCount, "Other's Bank-bound unit is not Smith's");
        CraftResult own = planner.evaluateAllCoordinated(List.of(ordinary), Map.of(), Map.of(),
                Map.of(PlanState.bankOwnerKey("Smith"), Map.of(IN, 1)), List.of(smith), tp, settings(), Set.of(-5)).get(-5);
        assertEquals(1, own.craftableCount, "the owner's own Bank-bound unit is usable by ordinary crafting");
    }

    @Test
    void oneBankCopyIsNeverSpentByBothOrdinaryAndForgeRoutes() {
        // Ordinary step needs 1 IN (crafted by Smith, spends Smith's single Bank unit); Forge parent needs that
        // ordinary output plus 1 IN directly. Only one physical IN exists: the plan must not double count it.
        Recipe mid = new Recipe(-6, ORD_OUT, 1, 0, "Weaponsmith", List.of(new Ingredient(IN, 1)), List.of(), RecipeSource.ORDINARY);
        Recipe top = new Recipe(-7, OUT, 1, 0, "", List.of(new Ingredient(ORD_OUT, 1), new Ingredient(IN, 1)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        CharacterCraftingProfile smith = new CharacterCraftingProfile("Smith", Map.of("Weaponsmith", 500));
        Map<Integer, PriceQuote> tp = Map.of(OUT, OUT_PRICE);
        CraftResult one = new CraftingPlanner().evaluateAllCoordinated(List.of(mid, top), Map.of(), Map.of(),
                Map.of(PlanState.bankOwnerKey("Smith"), Map.of(IN, 1)), List.of(smith), tp, settings(), Set.of(-6, -7)).get(-7);
        assertEquals(0, one.craftableCount, "one IN cannot feed both the ordinary step and the Forge ingredient");
        CraftResult two = new CraftingPlanner().evaluateAllCoordinated(List.of(mid, top), Map.of(), Map.of(),
                Map.of(PlanState.bankOwnerKey("Smith"), Map.of(IN, 2)), List.of(smith), tp, settings(), Set.of(-6, -7)).get(-7);
        assertEquals(1, two.craftableCount);
    }

    @Test
    void rejectedForgeRouteRollsBankStockBack() {
        PlanState state = new PlanState(Map.of(), Map.of(), Map.of(PlanState.bankOwnerKey("A"), Map.of(IN, 3)));
        int mark = state.mark();
        var used = state.consumeInventoryWithBinding(IN, 2, PlanState.forgeAssignee(null));
        assertEquals(2, used.usedBound());
        state.rollbackTo(mark);
        assertEquals(3, state.characterBoundInventory.get(PlanState.bankOwnerKey("A")).get(IN));
        assertEquals(0, state.consumedBoundQuantity(IN));
    }

    @Test
    void forgeNeverTouchesCarriedStockOfOtherOwners() {
        PlanState state = new PlanState(Map.of(), Map.of(), Map.of("A", Map.of(IN, 5)));
        assertEquals(0, state.consumeInventoryWithBinding(IN, 1, PlanState.forgeAssignee(null)).total());
        assertEquals(0, state.consumeInventoryWithBinding(IN, 1, PlanState.forgeAssignee("B")).total());
        assertEquals(1, state.consumeInventoryWithBinding(IN, 1, PlanState.forgeAssignee("A")).total());
    }

    @Test
    void ownersOrdinaryConsumptionSpendsCarriedBeforeBankAndNeverDoubleCounts() {
        PlanState state = new PlanState(Map.of(), Map.of(),
                Map.of("A", Map.of(IN, 1), PlanState.bankOwnerKey("A"), Map.of(IN, 1)));
        assertEquals(2, state.consumeInventoryWithBinding(IN, 5, "A").total());
        assertEquals(0, state.consumeInventoryWithBinding(IN, 1, PlanState.forgeAssignee(null)).total());
        assertEquals(2, state.consumedBoundQuantity(IN));
    }

    @Test
    void protectedBankItemIsUnavailableBecauseItIsNeverLoaded() {
        // Protection removes rows before aggregation (InventoryRepository); with no stock the Forge cannot run.
        assertEquals(0, run(forge(-8, 1), Map.of(), List.of(untrained("A")), Map.of()).craftableCount);
    }
}
