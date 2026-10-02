package craft;

import java.util.*;

public class CraftingGraph {

    private final List<Recipe> recipes;
    private final Map<Integer, List<Recipe>> recipesByOutput;
    private final Map<Integer, List<Recipe>> recipesByIngredient;
    private final Map<Integer, String> itemNames;

    public CraftingGraph(List<Recipe> recipes) {
        this(recipes, Map.of());
    }

    public CraftingGraph(List<Recipe> recipes, Map<Integer, String> itemNames) {
        this.recipes = List.copyOf(recipes);
        this.itemNames = Map.copyOf(itemNames);
        Map<Integer, List<Recipe>> byOutput = new HashMap<>();
        Map<Integer, List<Recipe>> byIngredient = new HashMap<>();

        for (Recipe recipe : this.recipes) {
            byOutput.computeIfAbsent(recipe.outputItemId, k -> new ArrayList<>()).add(recipe);
            for (Ingredient ingredient : recipe.ingredients) {
                byIngredient.computeIfAbsent(ingredient.itemId, k -> new ArrayList<>()).add(recipe);
            }
        }
        this.recipesByOutput = freeze(byOutput);
        this.recipesByIngredient = freeze(byIngredient);
    }

    public List<Recipe> getRecipes() {
        return recipes;
    }

    public Map<Integer, List<Recipe>> getRecipesByOutput() {
        return recipesByOutput;
    }

    public Map<Integer, List<Recipe>> getRecipesByIngredient() {
        return recipesByIngredient;
    }

    public Map<Integer, String> getItemNames() {
        return itemNames;
    }

    /**
     * Returns every recipe reachable by following ingredient-to-consumer edges and each consumer's
     * output item. The visited item set terminates cycles; recipe IDs deduplicate converging paths
     * and multiple recipes that produce the same item are all traversed.
     */
    public Set<Integer> parentRecipeIdsForIngredientIds(Set<Integer> ingredientItemIds) {
        Set<Integer> visitedItems = new HashSet<>();
        Set<Integer> matchedRecipes = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>(ingredientItemIds);
        while (!pending.isEmpty()) {
            int itemId = pending.removeFirst();
            if (!visitedItems.add(itemId)) continue;
            for (Recipe recipe : recipesByIngredient.getOrDefault(itemId, List.of())) {
                matchedRecipes.add(recipe.recipeId);
                if (!visitedItems.contains(recipe.outputItemId)) pending.addLast(recipe.outputItemId);
            }
        }
        return Collections.unmodifiableSet(matchedRecipes);
    }

    public Set<Integer> parentRecipeIdsForIngredientName(String searchText) {
        if (searchText == null || searchText.isBlank()) return Set.of();
        String needle = searchText.trim().toLowerCase(Locale.ROOT);
        Set<Integer> seedItems = new HashSet<>();
        itemNames.forEach((itemId, name) -> {
            if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) seedItems.add(itemId);
        });
        return parentRecipeIdsForIngredientIds(seedItems);
    }

    private static Map<Integer, List<Recipe>> freeze(Map<Integer, List<Recipe>> recipes) {
        Map<Integer, List<Recipe>> frozen = new HashMap<>();
        recipes.forEach((itemId, producers) -> frozen.put(itemId, List.copyOf(producers)));
        return Collections.unmodifiableMap(frozen);
    }
}
