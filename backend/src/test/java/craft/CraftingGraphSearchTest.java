package craft;

import org.junit.jupiter.api.Test;

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
