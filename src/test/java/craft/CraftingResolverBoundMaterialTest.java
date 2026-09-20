package craft;

import org.junit.jupiter.api.Test;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

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

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = Map.of();

        // A TP quote is supplied even though soulbound items cannot actually be sold, to prove
        // the bound-owned-quantity path ignores it entirely rather than happening to compute
        // zero because no quote existed.
        Map<Integer, TpPriceRepository.TpQuote> tp = Map.of(
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

        // No TP opportunity cost for the bound portion.
        assertEquals(0, need.getOpportunityCostCopper());

        // Bound pool was decremented, not the ordinary sellable pool.
        assertEquals(0, state.boundInventory.getOrDefault(SOULBOUND_TO_SELECTED_ITEM_ID, 0));
    }

    @Test
    void accountBoundItem_isUsableByAnyCharacterWithNoOpportunityCost() {
        int qtyOwned = 3;

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = Map.of();

        Map<Integer, TpPriceRepository.TpQuote> tp = Map.of(
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
    }

    @Test
    void soulboundItemBoundToDifferentCharacter_isNotUsableOwnedInventoryForSelectedCharacter() {
        int qtyRequested = 5;

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = Map.of();

        // Soulbound items cannot be sold on the Trading Post.
        Map<Integer, TpPriceRepository.TpQuote> tp = Map.of(
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
}
