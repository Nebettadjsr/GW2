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
            @Override public int countRecipeCurrencyRequirements() { return 0; }
            @Override public String publicationKey() { return "test-publication"; }
        };
        ItemRepository itemRepo = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(java.util.Set<Integer> ids) {
                return Map.of(10, new ItemInfo(10, "Cabbage", null), 20, new ItemInfo(20, "Soup", null));
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

    private CraftingGraphCache cacheOver(Path file, List<Recipe> dbRecipes) {
        previousCachePath = System.getProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, file.toString());
        RecipeRepository recipeRepo = new RecipeRepository() {
            @Override public List<Recipe> loadAllRecipes() { return List.copyOf(dbRecipes); }
            @Override public int countRecipes() { return dbRecipes.size(); }
            @Override public int countRecipeIngredients() { return 1; }
            @Override public int countRecipeCurrencyRequirements() { return 1; }
            @Override public String publicationKey() { return "test-publication"; }
        };
        ItemRepository itemRepo = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(java.util.Set<Integer> ids) {
                return Map.of(10, new ItemInfo(10, "Cabbage", null), 20, new ItemInfo(20, "Soup", null));
            }
        };
        return new CraftingGraphCache(recipeRepo, itemRepo);
    }

    @Test
    void legacyDiskSnapshotWithoutCurrencyFieldIsRebuiltFromTheCurrencyAwareDatabase() throws Exception {
        Path file = Files.createTempFile("gw2-graph-legacy", ".json");
        Files.writeString(file, """
                {"cacheKey":"recipes=1;ingredients=1","generatedAt":1,
                 "recipes":[{"recipeId":1,"outputItemId":20,"outputCount":2,"minRating":0,"disciplinesText":"",
                   "ingredients":[{"itemId":10,"count":1}]}],
                 "itemNames":{"10":"Cabbage","20":"Soup"}}""");
        Recipe withNotes = new Recipe(1, 20, 2, 0, "", List.of(new Ingredient(10, 1)),
                List.of(new craft.CurrencyRequirement(61, 30)));
        CraftingGraphCache cache = cacheOver(file, List.of(withNotes));

        CraftingGraph graph = cache.load();

        assertEquals(List.of(new craft.CurrencyRequirement(61, 30)), graph.getRecipes().get(0).currencies);
        // The rewritten file is now the current schema and is read back with its currency requirement.
        CraftingGraph reread = cacheOver(file, List.of(withNotes)).load();
        assertEquals(List.of(new craft.CurrencyRequirement(61, 30)), reread.getRecipes().get(0).currencies);
        Files.deleteIfExists(file);
    }

    @Test
    void modernItemOnlySnapshotStaysValidWithoutRebuilding() throws Exception {
        Path file = Files.createTempFile("gw2-graph-modern", ".json");
        Files.delete(file);
        Recipe plain = recipe(1, 20, 10);
        cacheOver(file, List.of(plain)).rebuild();
        // A fresh cache instance may reuse a current-schema file from the same source publication.
        CraftingGraph graph = cacheOver(file, List.of(plain)).load();
        assertEquals(1, graph.getRecipes().size());
        assertEquals(List.of(), graph.getRecipes().get(0).currencies);
        Files.deleteIfExists(file);
    }

    private static Recipe recipe(int recipeId, int output, int ingredient) {
        return new Recipe(recipeId, output, 2, 0, "", List.of(new Ingredient(ingredient, 1)));
    }
}
