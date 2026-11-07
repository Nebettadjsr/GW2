package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intended (spec-correct) behavior tests for DOMAIN_SPEC.md section 11.1 / DQ-007 ("Bound
 * Materials"), implemented per UD-001's Resolution: the selected character is an explicit
 * input, threaded in here as a pre-split {@code PlanState} (sellable owned quantity vs.
 * account-bound/soulbound-usable owned quantity) - the same split
 * {@code repo.InventoryRepository.loadOwnedInventoryForCharacter(String)} produces. These
 * tests supersede {@link CraftingResolverBoundMaterialCharacterizationTest} as the "does
 * section 3.4 have an intended-behavior test" answer for docs/ROADMAP.md Phase 0 exit
 * criterion 3; that characterization test is kept alongside this one because it still
 * accurately documents the still-unchanged, still-live flat-inventory production path
 * (single-arg {@code PlanState} constructor / {@code loadOwnedInventory()}) - see
 * STORY-DOM-011's Result section.
 */
class CraftingResolverBoundMaterialTest {

    private static final int SOULBOUND_TO_SELECTED_ITEM_ID = 400;
    private static final int ACCOUNT_BOUND_ITEM_ID = 401;
    private static final int SOULBOUND_TO_OTHER_ITEM_ID = 402;

    @Test
    void soulboundItemBoundToSelectedCharacter_isUsableWithNoOpportunityCost() {
        int qtyOwned = 5;

        Map<Integer, List<Recipe>> recipesByOutput = Map.of();

        // A TP quote is supplied even though soulbound items cannot actually be sold, to prove
        // the bound-owned-quantity path ignores it entirely rather than happening to compute
        // zero because no quote existed.
        Map<Integer, PriceQuote> tp = Map.of(
                SOULBOUND_TO_SELECTED_ITEM_ID, CraftTestFixtures.quote(200, 150)
        );

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());

        // As repo.InventoryRepository.loadOwnedInventoryForCharacter(String) would return it for
        // the selected character: this quantity lands entirely in the bound pool.
        PlanState state = CraftTestFixtures.stateWithBoundInventory(
                Map.of(),
                Map.of(SOULBOUND_TO_SELECTED_ITEM_ID, qtyOwned)
        );

        ResolvedNeed need = new CraftingResolver().resolveNeed(
                SOULBOUND_TO_SELECTED_ITEM_ID, qtyOwned, ctx, state, true);

        assertEquals(qtyOwned, need.getQtyFromInventory());
        assertEquals(0, need.getQtyBlocked());
        assertEquals(BlockedReason.NONE, need.getBlockedReason());
        assertTrue(need.isFullySatisfied());

        // No recipe or vendor value exists: explicit unvalued zero (section 11.2), not free.
        assertEquals(0, need.getOpportunityCostCopper());
        assertTrue(need.hasUnvaluedOwnedInput());

        // Bound pool was decremented, not the ordinary sellable pool.
        assertEquals(0, state.boundInventory.getOrDefault(SOULBOUND_TO_SELECTED_ITEM_ID, 0));
    }

    @Test
    void accountBoundItem_isUsableByAnyCharacterWithNoOpportunityCost() {
        int qtyOwned = 3;

        Map<Integer, List<Recipe>> recipesByOutput = Map.of();

        Map<Integer, PriceQuote> tp = Map.of(
                ACCOUNT_BOUND_ITEM_ID, CraftTestFixtures.quote(200, 150)
        );

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());

        // Account-bound quantity is usable regardless of which character is selected, so the
        // repository places it in the bound pool the same way as a soulbound-to-the-selected-
        // character quantity - the domain layer does not need to distinguish the two once split.
        PlanState state = CraftTestFixtures.stateWithBoundInventory(
                Map.of(),
                Map.of(ACCOUNT_BOUND_ITEM_ID, qtyOwned)
        );

        ResolvedNeed need = new CraftingResolver().resolveNeed(
                ACCOUNT_BOUND_ITEM_ID, qtyOwned, ctx, state, true);

        assertEquals(qtyOwned, need.getQtyFromInventory());
        assertEquals(0, need.getQtyBlocked());
        assertTrue(need.isFullySatisfied());
        assertEquals(0, need.getOpportunityCostCopper());
        assertTrue(need.hasUnvaluedOwnedInput());
    }

    @Test
    void soulboundItemBoundToDifferentCharacter_isNotUsableOwnedInventoryForSelectedCharacter() {
        int qtyRequested = 5;

        Map<Integer, List<Recipe>> recipesByOutput = Map.of();

        // Soulbound items cannot be sold on the Trading Post.
        Map<Integer, PriceQuote> tp = Map.of(
                SOULBOUND_TO_OTHER_ITEM_ID, CraftTestFixtures.noQuote()
        );

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());

        // repo.InventoryRepository.loadOwnedInventoryForCharacter(String) excludes a soulbound
        // row bound to a different character entirely - from the selected character's point of
        // view the owned quantity for this item is simply absent from both pools.
        PlanState state = CraftTestFixtures.stateWithBoundInventory(Map.of(), Map.of());

        ResolvedNeed need = new CraftingResolver().resolveNeed(
                SOULBOUND_TO_OTHER_ITEM_ID, qtyRequested, ctx, state, true);

        assertEquals(0, need.getQtyFromInventory());
        assertEquals(qtyRequested, need.getQtyBlocked());
        assertFalse(need.isFullySatisfied());
        assertEquals(BlockedReason.PRICE_UNAVAILABLE, need.getBlockedReason());
    }

    private static final int LUMP = 500, ORE = 501, CRYSTAL = 502, A = 503, B = 504;

    private static ResolvedNeed resolveBound(Map<Integer, List<Recipe>> recipes, Map<Integer, PriceQuote> tp,
                                             Map<Integer, Integer> vendor, CraftingSettings settings,
                                             Map<Integer, Integer> sellable, Map<Integer, Integer> bound,
                                             int item, int qty, PlanState[] out) {
        PlannerContext ctx = CraftTestFixtures.context(recipes, tp, settings, Set.of())
                .withVendorValues(vendor);
        PlanState state = CraftTestFixtures.stateWithBoundInventory(sellable, bound);
        out[0] = state;
        return new CraftingResolver().resolveNeed(item, qty, ctx, state, true);
    }

    private static Map<Integer, List<Recipe>> lumpRecipes() {
        // 3 Lump per craft from 10 ore (buy 8) + 2 crystal (buy 5): (80+10)/3 = 30 per unit
        return Map.of(LUMP, List.of(CraftTestFixtures.recipe(1, LUMP, 3, List.of(
                CraftTestFixtures.ingredient(ORE, 10), CraftTestFixtures.ingredient(CRYSTAL, 2)))));
    }

    private static Map<Integer, PriceQuote> lumpTp() {
        return Map.of(ORE, CraftTestFixtures.quote(8, 8), CRYSTAL, CraftTestFixtures.quote(5, 5));
    }

    @Test
    void boundOwnedCraftedItemIsValuedByRecursiveCraftingCostEvenWithBuyingDisabled() {
        CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, true);
        PlanState[] st = new PlanState[1];
        ResolvedNeed need = resolveBound(lumpRecipes(), lumpTp(), Map.of(LUMP, 1), settings,
                Map.of(), Map.of(LUMP, 4), LUMP, 4, st);
        // 4 units * 90/3 = 120 exactly; rounded once for the consumed quantity
        assertEquals(120, need.getOpportunityCostCopper());
        assertEquals(4, need.getQtyFromInventory());
        assertEquals(0, need.getQtyBought());
        assertEquals(0, need.getBuyCostCopper());
        assertFalse(need.hasUnvaluedOwnedInput());
    }

    @Test
    void fractionalUnitValueIsRoundedOnceForTheConsumedQuantity() {
        // 1 B from 1 A (cost 10), output 3 -> 10/3 per unit; 4 units -> ceil(40/3)=14 (not 4*4=16)
        Map<Integer, List<Recipe>> recipes = Map.of(B, List.of(CraftTestFixtures.recipe(1, B, 3,
                List.of(CraftTestFixtures.ingredient(A, 1)))));
        PlanState[] st = new PlanState[1];
        ResolvedNeed need = resolveBound(recipes, Map.of(A, CraftTestFixtures.quote(10, 10)), Map.of(),
                CraftTestFixtures.defaultSettings(), Map.of(), Map.of(B, 4), B, 4, st);
        assertEquals(14, need.getOpportunityCostCopper());
    }

    private static Map<Integer, List<Recipe>> fractionalBRecipes() {
        return Map.of(B, List.of(CraftTestFixtures.recipe(1, B, 3,
                List.of(CraftTestFixtures.ingredient(A, 1)))));
    }

    private static int resolveBoundCost(PlanState state, int qty) {
        PlannerContext ctx = CraftTestFixtures.context(fractionalBRecipes(),
                Map.of(A, CraftTestFixtures.quote(10, 10)), CraftTestFixtures.defaultSettings(), Set.of())
                .withVendorValues(Map.of());
        return new CraftingResolver().resolveNeed(B, qty, ctx, state, true).getOpportunityCostCopper();
    }

    @Test
    void splitBoundConsumptionChargesCumulativeRoundedTotal() {
        // 10 copper / 3 outputs; 1 + 1 + 2 units. Old per-call rounding: 4 + 4 + 7 = 15.
        PlanState state = CraftTestFixtures.stateWithBoundInventory(Map.of(), Map.of(B, 4));
        int first = resolveBoundCost(state, 1);
        int second = resolveBoundCost(state, 1);
        int third = resolveBoundCost(state, 2);
        assertEquals(4, first);   // ceil(10/3)
        assertEquals(3, second);  // ceil(20/3)=7 - 4
        assertEquals(7, third);   // ceil(40/3)=14 - 7
        assertEquals(14, first + second + third);
    }

    @Test
    void boundCumulativeChargeFollowsRollbackAndFreshState() {
        PlanState state = CraftTestFixtures.stateWithBoundInventory(Map.of(), Map.of(B, 4));
        int mark = state.mark();
        assertEquals(4, resolveBoundCost(state, 1));
        assertEquals(10, resolveBoundCost(state, 3)); // ceil(40/3)=14 minus the 4 already charged
        state.rollbackTo(mark);
        assertEquals(0, state.consumedBoundQuantity(B));
        // after reset the cumulative count restarts: same sequence gives the same charges
        assertEquals(4, resolveBoundCost(state, 1));
        assertEquals(3, resolveBoundCost(state, 1));
        assertEquals(7, resolveBoundCost(state, 2));
        PlanState fresh = CraftTestFixtures.stateWithBoundInventory(Map.of(), Map.of(B, 4));
        assertEquals(14, resolveBoundCost(fresh, 4));
    }

    @Test
    void vendorValueUsedWhenNoCompleteCraftingValueExists() {
        // ingredient has no price and no vendor value -> recipe incomplete -> vendor fallback
        Map<Integer, List<Recipe>> recipes = Map.of(LUMP, List.of(CraftTestFixtures.recipe(1, LUMP,
                List.of(CraftTestFixtures.ingredient(ORE, 1)))));
        PlanState[] st = new PlanState[1];
        ResolvedNeed need = resolveBound(recipes, Map.of(), Map.of(LUMP, 7),
                CraftTestFixtures.defaultSettings(), Map.of(), Map.of(LUMP, 2), LUMP, 2, st);
        assertEquals(14, need.getOpportunityCostCopper());
        assertFalse(need.hasUnvaluedOwnedInput());
    }

    @Test
    void cyclicRecipeIsNotZeroCostAndValidAlternativeStaysEligible() {
        Recipe cyc = CraftTestFixtures.recipe(1, A, List.of(CraftTestFixtures.ingredient(B, 1)));
        Recipe cyc2 = CraftTestFixtures.recipe(2, B, List.of(CraftTestFixtures.ingredient(A, 1)));
        Recipe ok = CraftTestFixtures.recipe(3, A, List.of(CraftTestFixtures.ingredient(ORE, 2)));
        PlanState[] st = new PlanState[1];
        ResolvedNeed need = resolveBound(Map.of(A, List.of(cyc, ok), B, List.of(cyc2)),
                Map.of(ORE, CraftTestFixtures.quote(6, 6)), Map.of(),
                CraftTestFixtures.defaultSettings(), Map.of(), Map.of(A, 1), A, 1, st);
        assertEquals(12, need.getOpportunityCostCopper());

        // pure cycle with no vendor value terminates and is flagged unvalued
        ResolvedNeed pure = resolveBound(Map.of(A, List.of(cyc), B, List.of(cyc2)), Map.of(), Map.of(),
                CraftTestFixtures.defaultSettings(), Map.of(), Map.of(A, 1), A, 1, st);
        assertEquals(0, pure.getOpportunityCostCopper());
        assertTrue(pure.hasUnvaluedOwnedInput());
    }

    @Test
    void mixedBoundAndSellableStockAreEachValuedOnce() {
        PlanState[] st = new PlanState[1];
        Map<Integer, PriceQuote> tp = Map.of(LUMP, CraftTestFixtures.quote(50, 40), ORE, CraftTestFixtures.quote(8, 8),
                CRYSTAL, CraftTestFixtures.quote(5, 5));
        ResolvedNeed need = resolveBound(lumpRecipes(), tp, Map.of(), CraftTestFixtures.defaultSettings(),
                Map.of(LUMP, 2), Map.of(LUMP, 3), LUMP, 5, st);
        // 3 bound * 30 + 2 sellable * instant-sell unit 50
        assertEquals(90 + 100, need.getOpportunityCostCopper());
        assertEquals(5, need.getQtyFromInventory());
        assertEquals(0, need.getQtyBought());
    }

    @Test
    void ownedBoundItemDoesNotConsumeOrBuyItsIngredients() {
        PlanState[] st = new PlanState[1];
        ResolvedNeed need = resolveBound(lumpRecipes(), lumpTp(), Map.of(), CraftTestFixtures.defaultSettings(),
                Map.of(ORE, 10), Map.of(LUMP, 3), LUMP, 3, st);
        assertEquals(10, st[0].inventory.getOrDefault(ORE, 0));
        assertEquals(0, need.getBuyCostCopper());
        assertEquals(90, need.getOpportunityCostCopper());
    }
}
