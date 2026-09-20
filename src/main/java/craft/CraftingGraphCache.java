package craft;

import com.fasterxml.jackson.databind.ObjectMapper;
import repo.RecipeRepository;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CraftingGraphCache {

    private static final String CACHE_FILE = "crafting_graph_cache.json";

    /**
     * Optional system property letting an in-process UI test point the cache at a fresh,
     * per-run temp file instead of the developer's real (possibly large, stale, or absent)
     * {@value #CACHE_FILE} in the working directory (STORY-UI-001, docs/TEST_STRATEGY.md).
     * Unset in normal/production use, so behavior is unchanged.
     */
    public static final String TEST_CACHE_FILE_PROPERTY = "gw2tool.test.craftingGraphCacheFile";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecipeRepository recipeRepo;

    public CraftingGraphCache(RecipeRepository recipeRepo) {
        this.recipeRepo = recipeRepo;
    }

    private static String cacheFilePath() {
        String override = System.getProperty(TEST_CACHE_FILE_PROPERTY);
        return (override != null && !override.isBlank()) ? override : CACHE_FILE;
    }

    public CraftingGraph load() throws IOException, SQLException {
        File file = new File(cacheFilePath());

        if (!file.exists()) {
            return rebuild();
        }

        CraftingGraphDto dto = mapper.readValue(file, CraftingGraphDto.class);
        return fromDto(dto);
    }

    public CraftingGraph rebuild() throws IOException, SQLException {
        File file = new File(cacheFilePath());

        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete old cache file: " + file.getAbsolutePath());
        }

        List<RecipeRepository.Recipe> recipes = recipeRepo.loadAllRecipes();
        CraftingGraph graph = new CraftingGraph(recipes);

        CraftingGraphDto dto = toDto(graph);
        dto.cacheKey = buildCacheKeyFromDb();
        dto.generatedAt = System.currentTimeMillis();

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, dto);

        System.out.println("Crafting graph cache rebuilt: " + file.getAbsolutePath());

        return graph;
    }

    private String buildCacheKeyFromDb() throws SQLException {
        int recipeCount = recipeRepo.countRecipes();
        int ingredientCount = recipeRepo.countRecipeIngredients();
        return "recipes=" + recipeCount + ";ingredients=" + ingredientCount;
    }

    private CraftingGraph fromDto(CraftingGraphDto dto) {
        List<RecipeRepository.Recipe> recipes = new ArrayList<>();

        for (CraftingGraphDto.RecipeDto r : dto.recipes) {
            List<RecipeRepository.Ingredient> ingredients = new ArrayList<>();

            for (CraftingGraphDto.IngredientDto ing : r.ingredients) {
                ingredients.add(new RecipeRepository.Ingredient(
                        ing.itemId,
                        ing.count
                ));
            }

            recipes.add(new RecipeRepository.Recipe(
                    r.recipeId,
                    r.outputItemId,
                    r.outputCount,
                    r.minRating,
                    r.disciplinesText,
                    ingredients
            ));
        }

        return new CraftingGraph(recipes);
    }

    private CraftingGraphDto toDto(CraftingGraph graph) {
        CraftingGraphDto dto = new CraftingGraphDto();
        dto.recipes = new ArrayList<>();

        for (RecipeRepository.Recipe r : graph.getRecipes()) {
            CraftingGraphDto.RecipeDto rd = new CraftingGraphDto.RecipeDto();
            rd.recipeId = r.recipeId;
            rd.outputItemId = r.outputItemId;
            rd.outputCount = r.outputCount;
            rd.minRating = r.minRating;
            rd.disciplinesText = r.disciplinesText;

            rd.ingredients = new ArrayList<>();

            for (RecipeRepository.Ingredient ing : r.ingredients) {
                CraftingGraphDto.IngredientDto id = new CraftingGraphDto.IngredientDto();
                id.itemId = ing.itemId;
                id.count = ing.count;
                rd.ingredients.add(id);
            }

            dto.recipes.add(rd);
        }

        return dto;
    }
}