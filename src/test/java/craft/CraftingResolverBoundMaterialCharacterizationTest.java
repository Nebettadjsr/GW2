package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for DOMAIN_SPEC.md section 11.1 ("Bound Materials") vs.
 * KNOWN_PROBLEMS.md section 3.4: the domain layer has no concept of account-bound or
 * soulbound materials, and no "selected character" concept at all. An owned quantity
 * that per section 11.1 "must not be counted as usable inventory for the selected
 * character" (a soulbound-to-another-character item) is today indistinguishable, at the
 * craft.PlanState/CraftingResolver level, from any other owned, tradable material.
 * <p>
 * These tests do NOT assert correct (spec-compliant) behavior - they pin down the
 * current, non-compliant behavior so that a future fix (which requires a
 * "selected character" concept - see agent/stories/BACKLOG.md) is a deliberate,
 * visible change rather than a silent one.
 */
class CraftingResolverBoundMaterialCharacterizationTest {

    private static final int SOULBOUND_MATERIAL_ITEM_ID = 300;

    @Test
    void shouldCurrentlyIgnoreSoulboundRestrictionsWhenConsumingOwnedMaterials() {
        // Scenario (DOMAIN_SPEC.md section 11.1): the owned quantity below represents a
        // soulbound item that is bound to a character other than the one currently
        // selected. Per spec it "must not be counted as usable inventory for the
        // selected character". The flat owned-quantity map craft.PlanState is built from
        // has no per-unit binding metadata and no selected-character concept at all, so
        // there is no way to express that restriction today - this quantity is just
        // ordinary owned inventory as far as the resolver is concerned.
        int qtyOwnedButSoulboundToAnotherCharacter = 5;

        Map<Integer, List<Recipe>> recipesByOutput = Map.of();

        // Soulbound items cannot be sold on the Trading Post, so no TP quote exists -
        // irrelevant here since the request is fully satisfied from "owned" inventory
        // before any buy/sell path is even considered.
        Map<Integer, PriceQuote> tp = Map.of(
                SOULBOUND_MATERIAL_ITEM_ID, CraftTestFixtures.noQuote()
        );

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());
        PlanState state = CraftTestFixtures.state(Map.of(
                SOULBOUND_MATERIAL_ITEM_ID, qtyOwnedButSoulboundToAnotherCharacter
        ));

        ResolvedNeed need = new CraftingResolver().resolveNeed(
                SOULBOUND_MATERIAL_ITEM_ID, qtyOwnedButSoulboundToAnotherCharacter, ctx, state, true);

        // Current (non-compliant) behavior: consumed exactly like ordinary owned
        // inventory - fully satisfied, nothing blocked, no binding-related rejection.
        assertEquals(qtyOwnedButSoulboundToAnotherCharacter, need.getQtyFromInventory());
        assertEquals(0, need.getQtyBlocked());
        assertEquals(BlockedReason.NONE, need.getBlockedReason());
        assertTrue(need.isFullySatisfied());

        // The PlanState inventory itself was decremented as normal usable stock, with no
        // trace of it having been off-limits to the selected character.
        assertEquals(0, state.inventory.getOrDefault(SOULBOUND_MATERIAL_ITEM_ID, 0));
    }
}
