package craft;

import java.util.List;

public class CraftingGraphDto {

    public String cacheKey;
    public long generatedAt;
    public List<RecipeDto> recipes;

    public static class RecipeDto {
        public int recipeId;
        public int outputItemId;
        public int outputCount;
        public int minRating;
        public String disciplinesText;
        public List<IngredientDto> ingredients;
    }

    public static class IngredientDto {
        public int itemId;
        public int count;
    }
}