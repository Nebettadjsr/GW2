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
    /**
     * Verified variable output, or {@code null} for a fixed output. When present {@link #outputCount} is the
     * guaranteed minimum: planning of nested steps, stock and feasibility use only that guarantee, while the
     * expected value feeds only estimated root economics.
     */
    public final ExpectedYield expectedYield;

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
        this(recipeId, outputItemId, outputCount, minRating, disciplinesText, ingredients, currencies, source, null);
    }

    /** A Mystic Forge recipe with a verified variable output; {@code outputCount} becomes the guaranteed minimum. */
    public static Recipe forgeWithExpectedYield(int recipeId, int outputItemId, ExpectedYield yield,
                                                List<Ingredient> ingredients) {
        return new Recipe(recipeId, outputItemId, yield.min(), 0, "", ingredients, List.of(),
                RecipeSource.MYSTIC_FORGE, yield);
    }

    private Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText,
                  List<Ingredient> ingredients, List<CurrencyRequirement> currencies, RecipeSource source,
                  ExpectedYield expectedYield) {
        if (expectedYield != null && (source != RecipeSource.MYSTIC_FORGE || outputCount != expectedYield.min())) {
            throw new IllegalArgumentException("expected yield is only for Mystic Forge recipes with outputCount == min");
        }
        this.expectedYield = expectedYield;
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

    public boolean hasExpectedYield() {
        return expectedYield != null;
    }

    /** Units of the output item this recipe itself consumes per craft (a promotion's self-seed). */
    public int selfSeedUnits() {
        int units = 0;
        for (Ingredient i : ingredients) if (i.itemId == outputItemId) units = Math.addExact(units, i.count);
        return units;
    }
}
