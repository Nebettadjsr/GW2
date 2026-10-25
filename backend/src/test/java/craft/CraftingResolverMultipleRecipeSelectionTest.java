package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.context;
import static craft.CraftTestFixtures.defaultSettings;
import static craft.CraftTestFixtures.emptyState;
import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for DOMAIN_SPEC.md section 30 / DQ-003: when an item has more
 * than one valid producing recipe, the resolver must (1) prefer a recipe matching the
 * parent recipe's crafting discipline, (2) among same-discipline candidates prefer the
 * lowest effective cost, and (3) otherwise prefer the lowest effective cost across all
 * remaining candidates. CraftingResolver.firstRecipeFor(itemId, ctx) currently just
 * returns the first candidate whose id is allowed, regardless of discipline or cost
 * (KNOWN_PROBLEMS.md section 3.2). These tests are expected to fail until STORY-DOM-002
 * implements the rule.
 */
class CraftingResolverMultipleRecipeSelectionTest {

    @Test
    void shouldPreferSameDisciplineRecipeOverCheaperCrossDisciplineRecipe() {
        int finalItemId = 1001;
        int intermediateItemId = 1002;
        int sameDisciplineRawId = 1101;
        int otherDisciplineRawId = 1102;

        Recipe finalRecipe = new Recipe(
                1, finalItemId, 1, 0, "Artificer",
                List.of(ingredient(intermediateItemId, 1))
        );
        // Matches the parent's discipline but is economically more expensive.
        Recipe sameDisciplineRecipe = new Recipe(
                10, intermediateItemId, 1, 0, "Artificer",
                List.of(ingredient(sameDisciplineRawId, 1))
        );
        // Cheaper, but a different discipline than the parent recipe.
        Recipe otherDisciplineRecipe = new Recipe(
                11, intermediateItemId, 1, 0, "Weaponsmith",
                List.of(ingredient(otherDisciplineRawId, 1))
        );

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                finalItemId, List.of(finalRecipe),
                // Cheaper/wrong-discipline recipe listed first, to expose a first-in-list bug.
                intermediateItemId, List.of(otherDisciplineRecipe, sameDisciplineRecipe)
        );

        Map<Integer, PriceQuote> tp = Map.of(
                sameDisciplineRawId, quote(null, 100),
                otherDisciplineRawId, quote(null, 50)
        );

        PlannerContext ctx = context(recipesByOutput, tp, defaultSettings(), Set.of(1, 10, 11));
        PlanState state = emptyState();

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);
        ResolvedNeed intermediateNeed = result.getRoot().getChildren().get(0);

        assertEquals(AcquisitionMode.CRAFT, intermediateNeed.getMode());
        ResolvedNeed rawNeed = intermediateNeed.getChildren().get(0);

        // DOMAIN_SPEC.md section 30 rule 1: same-discipline recipe wins despite higher cost.
        assertEquals(sameDisciplineRawId, rawNeed.getItemId());
        assertEquals(100, intermediateNeed.getEffectiveCostCopper());
    }

    @Test
    void shouldPreferCheaperRecipeAmongSameDisciplineCandidates() {
        int finalItemId = 2001;
        int intermediateItemId = 2002;
        int cheapRawId = 2101;
        int expensiveRawId = 2102;

        Recipe finalRecipe = new Recipe(
                2, finalItemId, 1, 0, "Artificer",
                List.of(ingredient(intermediateItemId, 1))
        );
        Recipe expensiveSameDisciplineRecipe = new Recipe(
                20, intermediateItemId, 1, 0, "Artificer",
                List.of(ingredient(expensiveRawId, 1))
        );
        Recipe cheapSameDisciplineRecipe = new Recipe(
                21, intermediateItemId, 1, 0, "Artificer",
                List.of(ingredient(cheapRawId, 1))
        );

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                finalItemId, List.of(finalRecipe),
                // More expensive recipe listed first, to expose a first-in-list bug.
                intermediateItemId, List.of(expensiveSameDisciplineRecipe, cheapSameDisciplineRecipe)
        );

        Map<Integer, PriceQuote> tp = Map.of(
                expensiveRawId, quote(null, 100),
                cheapRawId, quote(null, 50)
        );

        PlannerContext ctx = context(recipesByOutput, tp, defaultSettings(), Set.of(2, 20, 21));
        PlanState state = emptyState();

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);
        ResolvedNeed intermediateNeed = result.getRoot().getChildren().get(0);

        assertEquals(AcquisitionMode.CRAFT, intermediateNeed.getMode());
        ResolvedNeed rawNeed = intermediateNeed.getChildren().get(0);

        // DOMAIN_SPEC.md section 30 rule 2: cheaper of two same-discipline recipes wins.
        assertEquals(cheapRawId, rawNeed.getItemId());
        assertEquals(50, intermediateNeed.getEffectiveCostCopper());
    }

    @Test
    void shouldPreferCheapestRecipeWhenNoCandidateMatchesParentDiscipline() {
        int finalItemId = 3001;
        int intermediateItemId = 3002;
        int expensiveRawId = 3101;
        int cheapRawId = 3102;

        Recipe finalRecipe = new Recipe(
                3, finalItemId, 1, 0, "Artificer",
                List.of(ingredient(intermediateItemId, 1))
        );
        Recipe expensiveOtherDisciplineRecipe = new Recipe(
                30, intermediateItemId, 1, 0, "Tailor",
                List.of(ingredient(expensiveRawId, 1))
        );
        Recipe cheapOtherDisciplineRecipe = new Recipe(
                31, intermediateItemId, 1, 0, "Weaponsmith",
                List.of(ingredient(cheapRawId, 1))
        );

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                finalItemId, List.of(finalRecipe),
                // More expensive recipe listed first, to expose a first-in-list bug. Neither
                // candidate matches the parent's "Artificer" discipline.
                intermediateItemId, List.of(expensiveOtherDisciplineRecipe, cheapOtherDisciplineRecipe)
        );

        Map<Integer, PriceQuote> tp = Map.of(
                expensiveRawId, quote(null, 100),
                cheapRawId, quote(null, 50)
        );

        PlannerContext ctx = context(recipesByOutput, tp, defaultSettings(), Set.of(3, 30, 31));
        PlanState state = emptyState();

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);
        ResolvedNeed intermediateNeed = result.getRoot().getChildren().get(0);

        assertEquals(AcquisitionMode.CRAFT, intermediateNeed.getMode());
        ResolvedNeed rawNeed = intermediateNeed.getChildren().get(0);

        // DOMAIN_SPEC.md section 30 rule 3: cheapest overall wins when none match discipline.
        assertEquals(cheapRawId, rawNeed.getItemId());
        assertEquals(50, intermediateNeed.getEffectiveCostCopper());
    }
}
