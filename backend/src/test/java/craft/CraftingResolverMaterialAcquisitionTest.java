package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normal acquisition rules apply even when an ingredient has no Trading Post quote. */
class CraftingResolverMaterialAcquisitionTest {
    private static final int OUTPUT = 100;
    private static final int BOUND_MATERIAL = 200;
    private static final int INTERMEDIATE = 300;
    private static final int PART = 400;

    private static final Recipe FROM_BOUND = CraftTestFixtures.recipe(1, OUTPUT,
            List.of(new Ingredient(BOUND_MATERIAL, 1)));
    private static final Recipe FROM_INTERMEDIATE = CraftTestFixtures.recipe(2, OUTPUT,
            List.of(new Ingredient(INTERMEDIATE, 1)));
    private static final Recipe MAKE_INTERMEDIATE = CraftTestFixtures.recipe(3, INTERMEDIATE,
            List.of(new Ingredient(PART, 1)));

    private static final Map<Integer, PriceQuote> PRICES = Map.of(
            OUTPUT, CraftTestFixtures.quote(900, 1000),
            BOUND_MATERIAL, CraftTestFixtures.noQuote(),
            INTERMEDIATE, CraftTestFixtures.noQuote(),
            PART, CraftTestFixtures.quote(10, 12));

    @Test
    void ownedAccountBoundMaterialWithNoQuoteIsConsumed() {
        PlannerContext ctx = CraftTestFixtures.context(
                Map.of(OUTPUT, List.of(FROM_BOUND)), PRICES,
                CraftTestFixtures.defaultSettings(), Set.of(FROM_BOUND.recipeId));
        PlanState state = new PlanState(Map.of(), Map.of(BOUND_MATERIAL, 1));

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_BOUND, ctx, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(BlockedReason.NONE, root.getBlockedReason());
        assertEquals(0, state.boundInventory.getOrDefault(BOUND_MATERIAL, 0));
    }

    @Test
    void unquotedIntermediateIsCraftedRecursively() {
        PlannerContext ctx = CraftTestFixtures.context(
                Map.of(OUTPUT, List.of(FROM_INTERMEDIATE), INTERMEDIATE, List.of(MAKE_INTERMEDIATE)),
                PRICES, CraftTestFixtures.defaultSettings(),
                Set.of(FROM_INTERMEDIATE.recipeId, MAKE_INTERMEDIATE.recipeId));
        PlanState state = CraftTestFixtures.state(Map.of(PART, 1));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_INTERMEDIATE, ctx, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(BlockedReason.NONE, root.getBlockedReason());
        assertEquals(0, state.inventory.getOrDefault(PART, 0));
    }

    @Test
    void unownedUncraftableUnquotedIngredientBlocksNaturally() {
        PlannerContext ctx = CraftTestFixtures.context(
                Map.of(OUTPUT, List.of(FROM_BOUND)), PRICES,
                CraftTestFixtures.defaultSettings(), Set.of(FROM_BOUND.recipeId));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_BOUND, ctx, CraftTestFixtures.emptyState()).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.PRICE_UNAVAILABLE, root.getBlockedReason());
        assertEquals(0, root.getQtyBought());
    }
}
