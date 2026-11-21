package web;

import craft.CraftingGraph;
import craft.Ingredient;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.CraftingGraphCache;
import repo.RecipeRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void bareIngredientIdReturnsSortedCachedConsumersAndUnionsNameMatchesOverHttp() throws Exception {
        CraftingGraph graph = new CraftingGraph(List.of(
                recipe(9, 300, 200), recipe(2, 200, 19698), recipe(5, 201, 19698),
                recipe(7, 200, 300), recipe(1, 800, 500), recipe(99, 900, 196980)),
                Map.of(500, "Commemorative 19698 Token"));
        MockMvc mvc = mvc(graph);

        for (String query : List.of("19698", " 19698 ")) {
            mvc.perform(get("/api/crafting/ingredient-search").param("query", query))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"recipeIds\":[1,2,5,7,9]}", true));
        }
    }

    @Test
    void malformedNumericHttpQueriesReturnEmptyResultsWithoutFailing() throws Exception {
        MockMvc mvc = mvc(new CraftingGraph(List.of(
                recipe(1, 100, 19698), recipe(2, 200, Integer.MAX_VALUE))));

        for (String query : List.of("0", "-19698", "19698.0", "19698x", "2147483648",
                "9999999999999999999999999999999")) {
            mvc.perform(get("/api/crafting/ingredient-search").param("query", query))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"recipeIds\":[]}", true));
        }
    }

    @Test
    void forgeOnlyInputsCannotReachOrdinaryParentsThroughRemovedForgeEdges() throws Exception {
        // 20796 (a Stone) is named only by item metadata and consumed solely by a Forge edge: search is ordinary-only.
        CraftingGraph graph = new CraftingGraph(List.of(recipe(2, 200, 100), recipe(9, 300, 200)),
                Map.of(100, "Cabbage", 200, "Intermediate", 300, "Finished", 20796, "Philosopher's Stone"));
        MockMvc mvc = mvc(graph);
        mvc.perform(get("/api/crafting/ingredient-search").param("query", "philosopher"))
                .andExpect(status().isOk()).andExpect(content().json("{\"recipeIds\":[]}", true));
        mvc.perform(get("/api/crafting/ingredient-search").param("query", "20796"))
                .andExpect(content().json("{\"recipeIds\":[]}", true));
        mvc.perform(get("/api/crafting/ingredient-search").param("query", "cabbage"))
                .andExpect(content().json("{\"recipeIds\":[2,9]}", true));
    }

    private static MockMvc mvc(CraftingGraph graph) {
        CraftingGraphCache cache = new CraftingGraphCache(new RecipeRepository()) {
            @Override public CraftingGraph load() { return graph; }
        };
        return MockMvcBuilders.standaloneSetup(new CraftingIngredientSearchApiController(cache))
                .defaultRequest(AuthorizedRequests.defaultRequest()).build();
    }

    private static Recipe recipe(int id, int output, int ingredient) {
        return new Recipe(id, output, 1, 0, "", List.of(new Ingredient(ingredient, 1)));
    }
}
