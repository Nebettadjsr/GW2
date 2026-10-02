package web;

import craft.CraftingGraph;
import craft.Ingredient;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.RecipeRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingIngredientSearchApiControllerTest {
    @Test
    void returnsOnlySortedRecipeIdsFromTheCachedStaticGraph() throws Exception {
        CraftingGraph graph = new CraftingGraph(List.of(
                recipe(9, 300, 200), recipe(2, 200, 100), recipe(5, 201, 100)),
                Map.of(100, "Cabbage", 200, "Intermediate", 201, "Other", 300, "Finished"));
        CraftingGraphCache cache = new CraftingGraphCache(new RecipeRepository()) {
            @Override public CraftingGraph load() { return graph; }
        };
        var controller = new CraftingIngredientSearchApiController(cache);

        assertEquals(List.of(2, 5, 9), controller.search("cabbage").recipeIds());
        assertEquals(List.of(), controller.search("unmatched").recipeIds());
    }

    private static Recipe recipe(int id, int output, int ingredient) {
        return new Recipe(id, output, 1, 0, "", List.of(new Ingredient(ingredient, 1)));
    }
}
