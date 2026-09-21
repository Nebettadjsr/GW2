package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for DOMAIN_SPEC.md section 21: a missing/unusable Trading Post
 * price must not be interpreted as a free item. See also section 42 (Blocked Reasons).
 */
class CraftingResolverPriceUnavailableTest {

    private static final int RAW_MATERIAL_ITEM_ID = 200;

    @Test
    void shouldMarkPriceUnavailableInsteadOfZeroCostBuy() {
        // RawMaterial has no recipe and no usable TP quote at all -> a required purchase
        // of it must be flagged as price-unavailable, not silently resolved as a free/
        // zero-cost purchase (DOMAIN_SPEC.md section 21).
        Map<Integer, List<Recipe>> recipesByOutput = Map.of();

        Map<Integer, PriceQuote> tp = Map.of(
                RAW_MATERIAL_ITEM_ID, CraftTestFixtures.noQuote()
        );

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());
        PlanState state = CraftTestFixtures.emptyState();

        ResolvedNeed rawMaterialNeed = new CraftingResolver()
                .resolveNeed(RAW_MATERIAL_ITEM_ID, 1, ctx, state, true);

        assertEquals(BlockedReason.PRICE_UNAVAILABLE, rawMaterialNeed.getBlockedReason());
        assertTrue(rawMaterialNeed.getQtyBlocked() > 0);
        assertEquals(0, rawMaterialNeed.getQtyBought());
        assertEquals(0, rawMaterialNeed.getBuyCostCopper());
        assertFalse(rawMaterialNeed.isFullySatisfied());
    }
}
