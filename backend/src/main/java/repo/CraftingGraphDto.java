package repo;

import java.util.List;
import java.util.Map;

/** JSON transport shape for {@link CraftingGraphCache}'s on-disk crafting graph cache. */
public class CraftingGraphDto {

    /** Cache layout version; a snapshot written before currency requirements existed lacks it and is rebuilt. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public int schemaVersion;
    public String cacheKey;
    public long generatedAt;
    public List<RecipeDto> recipes;
    public Map<Integer, String> itemNames;

    public static class RecipeDto {
        public int recipeId;
        public int outputItemId;
        public int outputCount;
        public int minRating;
        public String disciplinesText;
        public List<IngredientDto> ingredients;
        /** Currency requirements (currency id, never item id); absent in caches written before currencies. */
        public List<IngredientDto> currencies;
    }

    public static class IngredientDto {
        public int itemId;
        public int count;
    }
}
