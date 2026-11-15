package craft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingGraphSearchTest {

    @Test
    void findsDirectAndMultiLevelParentsAcrossEveryProducerAndDeduplicatesCyclesAndPaths() {
        Recipe directA = recipe(10, 100, 2, 1, 2);
        Recipe directB = recipe(11, 100, 1, 1);
        Recipe parentA = recipe(20, 200, 3, 100, 9);
        Recipe parentB = recipe(21, 201, 1, 100);
        Recipe converging = recipe(30, 300, 1, 200, 201);
        Recipe cycle = recipe(40, 100, 1, 300);
        CraftingGraph graph = new CraftingGraph(List.of(directA, directB, parentA, parentB, converging, cycle));

        assertEquals(Set.of(10, 11, 20, 21, 30, 40),
                graph.parentRecipeIdsForIngredientIds(Set.of(1)));
    }

    @Test
    void searchesNamesAndReturnsNoParentsForUnknownTerms() {
        CraftingGraph graph = new CraftingGraph(
                List.of(recipe(1, 20, 4, 10)), Map.of(10, "Fresh Cabbage", 20, "Cabbage Soup"));

        assertEquals(Set.of(1), graph.parentRecipeIdsForIngredientName("cAbBaGe"));
        assertEquals(Set.of(), graph.parentRecipeIdsForIngredientName("no such item"));
        assertEquals(Set.of(), graph.parentRecipeIdsForIngredientName(" "));
    }

    @Test
    void searchesUncachedIngredientIdAcrossEveryProducerAndDeduplicatesCyclesAndPaths() {
        CraftingGraph graph = new CraftingGraph(List.of(
                recipe(10, 100, 1, 19698), recipe(11, 100, 2, 19698),
                recipe(20, 200, 1, 100), recipe(30, 300, 1, 100, 200),
                recipe(40, 100, 1, 300), recipe(50, 500, 1, 196980)));

        assertEquals(Set.of(10, 11, 20, 30, 40),
                graph.parentRecipeIdsForIngredientName(" 19698 "));
    }

    @Test
    void numericSearchUnionsIngredientIdentityWithExistingNameMatches() {
        CraftingGraph graph = new CraftingGraph(List.of(
                recipe(10, 100, 1, 19698), recipe(20, 200, 1, 500),
                recipe(30, 300, 1, 100, 200)), Map.of(500, "Commemorative 19698 Token"));

        assertEquals(Set.of(10, 20, 30), graph.parentRecipeIdsForIngredientName("19698"));
        assertEquals(Set.of(20, 30), graph.parentRecipeIdsForIngredientName("cOmMeMoRaTiVe"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void searchesPositiveIngredientIdsThroughoutTheSupportedIntegerRange(int itemId) {
        CraftingGraph graph = new CraftingGraph(List.of(recipe(10, 100, 1, itemId)));

        assertEquals(Set.of(10), graph.parentRecipeIdsForIngredientName(Integer.toString(itemId)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-19698", "19698.0", "19698x", "2147483648",
            "9999999999999999999999999999999"})
    void malformedOrOutOfRangeTextDoesNotBecomeAnIngredientId(String query) {
        CraftingGraph graph = new CraftingGraph(List.of(
                recipe(10, 100, 1, 19698), recipe(20, 200, 1, Integer.MAX_VALUE)));

        assertEquals(Set.of(), graph.parentRecipeIdsForIngredientName(query));
    }

    @Test
    void outOfRangeNumericTextStillUsesOrdinaryNameMatching() {
        CraftingGraph graph = new CraftingGraph(List.of(recipe(10, 100, 1, 500)),
                Map.of(500, "Token 2147483648"));

        assertEquals(Set.of(10), graph.parentRecipeIdsForIngredientName("2147483648"));
    }

    @Test
    void textThatBecomesEmptyAfterTrimmingDoesNotMatchAllNamedIngredients() {
        CraftingGraph graph = new CraftingGraph(List.of(recipe(10, 100, 1, 19698)),
                Map.of(19698, "Mithril Ore"));

        assertEquals(Set.of(), graph.parentRecipeIdsForIngredientName(String.valueOf((char) 0)));
    }

    @Test
    void widelyUsedIngredientTraversalReturnsEachRecipeOnce() {
        var recipes = new java.util.ArrayList<Recipe>();
        for (int i = 1; i <= 2_000; i++) recipes.add(recipe(i, 10_000 + i, 1, 7));
        CraftingGraph graph = new CraftingGraph(recipes);

        assertEquals(2_000, graph.parentRecipeIdsForIngredientIds(Set.of(7)).size());
    }

    private static Recipe recipe(int recipeId, int output, int outputCount, int... ingredientIds) {
        return new Recipe(recipeId, output, outputCount, 0, "", java.util.Arrays.stream(ingredientIds)
                .mapToObj(id -> new Ingredient(id, 1)).toList());
    }
}
