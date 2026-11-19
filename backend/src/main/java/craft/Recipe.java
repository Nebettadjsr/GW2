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
    /** Typed currency requirements per craft (never item ids); empty for item-only recipes. */
    public final List<CurrencyRequirement> currencies;
    /** Where the recipe comes from; ordinary ArenaNet recipes unless explicitly a Mystic Forge recipe. */
    public final RecipeSource source;

    public Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText, List<Ingredient> ingredients) {
        this(recipeId, outputItemId, outputCount, minRating, disciplinesText, ingredients, List.of());
    }

    public Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText,
                  List<Ingredient> ingredients, List<CurrencyRequirement> currencies) {
        this(recipeId, outputItemId, outputCount, minRating, disciplinesText, ingredients, currencies,
                RecipeSource.ORDINARY);
    }

    public Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText,
                  List<Ingredient> ingredients, List<CurrencyRequirement> currencies, RecipeSource source) {
        this.source = source;
        this.currencies = List.copyOf(currencies);
        this.recipeId = recipeId;
        this.outputItemId = outputItemId;
        this.outputCount = outputCount;
        this.minRating = minRating;
        this.disciplinesText = disciplinesText;
        this.ingredients = ingredients;
    }

    public boolean isMysticForge() {
        return source == RecipeSource.MYSTIC_FORGE;
    }
}
