package craft;

import java.util.List;

/** Independent crafting-domain recipe (STORY-DOM-017): no persistence/JDBC dependency. */
public class Recipe {
    public final int recipeId;
    public final int outputItemId;
    public final int outputCount;
    public final int minRating;
    public final String disciplinesText; // "Artificer, Tailor, ..."
    public final List<Ingredient> ingredients;

    public Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText, List<Ingredient> ingredients) {
        this.recipeId = recipeId;
        this.outputItemId = outputItemId;
        this.outputCount = outputCount;
        this.minRating = minRating;
        this.disciplinesText = disciplinesText;
        this.ingredients = ingredients;
    }
}
