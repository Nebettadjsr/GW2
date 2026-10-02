package repo;

import craft.CraftingGraph;
import craft.Ingredient;
import craft.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CraftingGraphCacheSearchTest {
    private String previousCachePath;

    @AfterEach
    void restoreCachePath() {
        if (previousCachePath == null) System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        else System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, previousCachePath);
    }

    @Test
    void holdsOneCompleteGraphSnapshotAndRebuildReplacesIt() throws Exception {
        previousCachePath = System.getProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        Path file = Files.createTempFile("gw2-graph-search", ".json");
        Files.delete(file);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, file.toString());
        var recipes = new java.util.ArrayList<Recipe>(List.of(recipe(1, 20, 10)));
        RecipeRepository recipeRepo = new RecipeRepository() {
            @Override public List<Recipe> loadAllRecipes() { return List.copyOf(recipes); }
            @Override public int countRecipes() { return recipes.size(); }
            @Override public int countRecipeIngredients() { return 1; }
        };
        ItemRepository itemRepo = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(java.util.Set<Integer> ids) {
                return Map.of(10, new ItemInfo(10, "Cabbage", null, null), 20, new ItemInfo(20, "Soup", null, null));
            }
        };
        CraftingGraphCache cache = new CraftingGraphCache(recipeRepo, itemRepo);

        CraftingGraph first = cache.load();
        assertSame(first, cache.load());
        assertEquals(java.util.Set.of(1), first.parentRecipeIdsForIngredientName("cabbage"));

        recipes.add(recipe(2, 30, 10));
        cache.invalidate();
        CraftingGraph second = cache.load();
        assertEquals(java.util.Set.of(1, 2), second.parentRecipeIdsForIngredientName("cabbage"));
        assertEquals(java.util.Set.of(1), first.parentRecipeIdsForIngredientName("cabbage"));
        Files.deleteIfExists(file);
    }

    private static Recipe recipe(int recipeId, int output, int ingredient) {
        return new Recipe(recipeId, output, 2, 0, "", List.of(new Ingredient(ingredient, 1)));
    }
}
