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
 * Regression coverage for DOMAIN_SPEC.md section 42: an item whose only producing
 * recipe is filtered out by ctx.allowedRecipeIds must be reported as
 * RECIPE_NOT_ALLOWED, distinct from an item with no producing recipe at all (NO_RECIPE).
 */
class CraftingResolverRecipeNotAllowedTest {

    private static final int FILTERED_ITEM_ID = 400;
    private static final int FILTERED_RECIPE_ID = 40;
    private static final int INGREDIENT_ITEM_ID = 401;

    @Test
    void shouldMarkRecipeNotAllowedWhenOnlyCandidateIsFilteredOut() {
        // FilteredItem has exactly one producing recipe, but that recipe's id is not in
        // ctx.allowedRecipeIds. With direct buying disallowed at this node, the resolver's
        // only possible path is that filtered-out recipe.
        RecipeRepository.Recipe filteredRecipe = CraftTestFixtures.recipe(
                FILTERED_RECIPE_ID, FILTERED_ITEM_ID, List.of(CraftTestFixtures.ingredient(INGREDIENT_ITEM_ID, 1))
        );

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = Map.of(
                FILTERED_ITEM_ID, List.of(filteredRecipe)
        );

        Map<Integer, TpPriceRepository.TpQuote> tp = Map.of();

        CraftingSettings settings = CraftTestFixtures.defaultSettings();
        // FILTERED_RECIPE_ID is deliberately excluded from allowedRecipeIds.
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, Set.of());
        PlanState state = CraftTestFixtures.emptyState();

        ResolvedNeed need = new CraftingResolver().resolveNeed(FILTERED_ITEM_ID, 1, ctx, state, false);

        assertEquals(BlockedReason.RECIPE_NOT_ALLOWED, need.getBlockedReason());
        assertTrue(need.getQtyBlocked() > 0);
        assertFalse(need.isFullySatisfied());
    }
}
