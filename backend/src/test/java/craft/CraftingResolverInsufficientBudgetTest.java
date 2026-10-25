package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for DOMAIN_SPEC.md section 19: when a positive maxBuyCopper budget
 * is the specific reason a simulation could not proceed (not NO_RECIPE/PRICE_UNAVAILABLE/
 * an unaffordable-but-otherwise-fine plan), the result must be marked
 * BlockedReason.INSUFFICIENT_BUDGET rather than left indistinguishable from "not
 * profitable" or an unset reason.
 */
class CraftingResolverInsufficientBudgetTest {

    private static final int OUTPUT_ITEM_ID = 500;
    private static final int OUTPUT_RECIPE_ID = 50;
    private static final int INGREDIENT_ITEM_ID = 501;

    @Test
    void shouldMarkInsufficientBudgetWhenBudgetBlocksEvenFirstCraft() {
        Recipe recipe = CraftTestFixtures.recipe(
                OUTPUT_RECIPE_ID, OUTPUT_ITEM_ID, List.of(CraftTestFixtures.ingredient(INGREDIENT_ITEM_ID, 1))
        );

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                OUTPUT_ITEM_ID, List.of(recipe)
        );

        // Ingredient costs 100 copper to buy, but the budget is only 50 - not enough to
        // cover even a single craft's required purchase.
        Map<Integer, PriceQuote> tp = Map.of(
                INGREDIENT_ITEM_ID, CraftTestFixtures.quote(null, 100)
        );

        CraftingSettings settings = new CraftingSettings(true, true, 50, false, false, false);
        PlannerContext ctx = CraftTestFixtures.context(recipesByOutput, tp, settings, java.util.Set.of(OUTPUT_RECIPE_ID));
        PlanState baseState = CraftTestFixtures.emptyState();

        RecipeSimulationResult result = new RecipeSimulator().simulateRecipe(recipe, ctx, baseState);

        assertEquals(0, result.getCraftCount());
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, result.getBlockedReason());
    }
}
