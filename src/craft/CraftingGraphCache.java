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

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecipeRepository recipeRepo;

    public CraftingGraphCache(RecipeRepository recipeRepo) {
        this.recipeRepo = recipeRepo;
    }

    public CraftingGraph load() throws IOException {
        File file = new File(CACHE_FILE);

        if (!file.exists()) {
            throw new IOException("Crafting graph cache file not found: " + file.getAbsolutePath());
        }

        CraftingGraphDto dto = mapper.readValue(file, CraftingGraphDto.class);
        return fromDto(dto);
    }

    public CraftingGraph rebuild() throws IOException, SQLException {
        File file = new File(CACHE_FILE);

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

    public boolean cacheExists() {
        return new File(CACHE_FILE).exists();
    }

    public String getCacheFilePath() {
        return new File(CACHE_FILE).getAbsolutePath();
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