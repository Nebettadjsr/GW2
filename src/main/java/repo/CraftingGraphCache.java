package repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftingGraph;
import craft.Ingredient;
import craft.Recipe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence-backed cache of the crafting graph, mapping cached JSON rows into the independent
 * {@code craft.Recipe}/{@code craft.Ingredient} domain types (STORY-DOM-017): this class - not
 * {@code craft.*} - owns both the database load and the on-disk cache (de)serialization boundary,
 * per TARGET_ARCHITECTURE.md section 10.
 */
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
    private final ItemRepository itemRepo;
    private volatile CraftingGraph cachedGraph;
    private volatile boolean invalidated;

    public CraftingGraphCache(RecipeRepository recipeRepo) {
        this(recipeRepo, new ItemRepository());
    }

    public CraftingGraphCache(RecipeRepository recipeRepo, ItemRepository itemRepo) {
        this.recipeRepo = recipeRepo;
        this.itemRepo = itemRepo;
    }

    private static String cacheFilePath() {
        String override = System.getProperty(TEST_CACHE_FILE_PROPERTY);
        return (override != null && !override.isBlank()) ? override : CACHE_FILE;
    }

    public CraftingGraph load() throws IOException, SQLException {
        CraftingGraph snapshot = cachedGraph;
        if (snapshot != null && !invalidated) return snapshot;
        synchronized (this) {
            snapshot = cachedGraph;
            if (snapshot != null && !invalidated) return snapshot;
            if (invalidated) return rebuild();
            File file = new File(cacheFilePath());
            if (!file.exists()) return rebuild();

            CraftingGraphDto dto = mapper.readValue(file, CraftingGraphDto.class);
            Map<Integer, String> names = dto.itemNames;
            if (names == null || names.isEmpty()) {
                names = loadItemNames(dto.recipes);
                dto.itemNames = names;
                writeAtomically(file.toPath(), dto);
            }
            snapshot = fromDto(dto, names);
            cachedGraph = snapshot;
            return snapshot;
        }
    }

    public synchronized CraftingGraph rebuild() throws IOException, SQLException {
        File file = new File(cacheFilePath());
        List<Recipe> recipes = recipeRepo.loadAllRecipes();
        Set<Integer> itemIds = new java.util.HashSet<>();
        for (Recipe recipe : recipes) {
            itemIds.add(recipe.outputItemId);
            recipe.ingredients.forEach(ingredient -> itemIds.add(ingredient.itemId));
        }
        Map<Integer, String> itemNames = new java.util.HashMap<>();
        itemRepo.loadItems(itemIds).forEach((itemId, item) -> {
            if (item.name != null) itemNames.put(itemId, item.name);
        });
        CraftingGraph graph = new CraftingGraph(recipes, itemNames);

        CraftingGraphDto dto = toDto(graph);
        dto.cacheKey = buildCacheKeyFromDb();
        dto.generatedAt = System.currentTimeMillis();

        writeAtomically(file.toPath(), dto);
        cachedGraph = graph;
        invalidated = false;

        System.out.println("Crafting graph cache rebuilt: " + file.getAbsolutePath());

        return graph;
    }

    /** Marks the persisted and in-memory catalog snapshot stale after recipe synchronization. */
    public synchronized void invalidate() {
        invalidated = true;
        cachedGraph = null;
    }

    private String buildCacheKeyFromDb() throws SQLException {
        int recipeCount = recipeRepo.countRecipes();
        int ingredientCount = recipeRepo.countRecipeIngredients();
        return "recipes=" + recipeCount + ";ingredients=" + ingredientCount;
    }

    private CraftingGraph fromDto(CraftingGraphDto dto, Map<Integer, String> itemNames) {
        List<Recipe> recipes = new ArrayList<>();

        for (CraftingGraphDto.RecipeDto r : dto.recipes) {
            List<Ingredient> ingredients = new ArrayList<>();

            for (CraftingGraphDto.IngredientDto ing : r.ingredients) {
                ingredients.add(new Ingredient(
                        ing.itemId,
                        ing.count
                ));
            }

            recipes.add(new Recipe(
                    r.recipeId,
                    r.outputItemId,
                    r.outputCount,
                    r.minRating,
                    r.disciplinesText,
                    ingredients
            ));
        }

        return new CraftingGraph(recipes, itemNames);
    }

    private CraftingGraphDto toDto(CraftingGraph graph) {
        CraftingGraphDto dto = new CraftingGraphDto();
        dto.recipes = new ArrayList<>();
        dto.itemNames = graph.getItemNames();

        for (Recipe r : graph.getRecipes()) {
            CraftingGraphDto.RecipeDto rd = new CraftingGraphDto.RecipeDto();
            rd.recipeId = r.recipeId;
            rd.outputItemId = r.outputItemId;
            rd.outputCount = r.outputCount;
            rd.minRating = r.minRating;
            rd.disciplinesText = r.disciplinesText;

            rd.ingredients = new ArrayList<>();

            for (Ingredient ing : r.ingredients) {
                CraftingGraphDto.IngredientDto id = new CraftingGraphDto.IngredientDto();
                id.itemId = ing.itemId;
                id.count = ing.count;
                rd.ingredients.add(id);
            }

            dto.recipes.add(rd);
        }

        return dto;
    }

    private Map<Integer, String> loadItemNames(List<CraftingGraphDto.RecipeDto> recipes) throws SQLException {
        Set<Integer> itemIds = new java.util.HashSet<>();
        for (CraftingGraphDto.RecipeDto recipe : recipes) {
            itemIds.add(recipe.outputItemId);
            if (recipe.ingredients != null) recipe.ingredients.forEach(ingredient -> itemIds.add(ingredient.itemId));
        }
        Map<Integer, String> names = new java.util.HashMap<>();
        itemRepo.loadItems(itemIds).forEach((itemId, item) -> {
            if (item.name != null) names.put(itemId, item.name);
        });
        return names;
    }

    private void writeAtomically(Path destination, CraftingGraphDto dto) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), dto);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
