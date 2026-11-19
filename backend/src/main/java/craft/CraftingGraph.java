package craft;

import java.util.*;

public class CraftingGraph {

    private final List<Recipe> recipes;
    private final Map<Integer, List<Recipe>> recipesByIngredient;
    private final Map<Integer, String> itemNames;

    public CraftingGraph(List<Recipe> recipes) {
        this(recipes, Map.of());
    }

    public CraftingGraph(List<Recipe> recipes, Map<Integer, String> itemNames) {
        this.recipes = List.copyOf(recipes);
        this.itemNames = Map.copyOf(itemNames);
        Map<Integer, List<Recipe>> byIngredient = new HashMap<>();

        for (Recipe recipe : this.recipes) {
            for (Ingredient ingredient : recipe.ingredients) {
                byIngredient.computeIfAbsent(ingredient.itemId, k -> new ArrayList<>()).add(recipe);
            }
        }
        this.recipesByIngredient = freeze(byIngredient);
    }

    public List<Recipe> getRecipes() {
        return recipes;
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
        return parentRecipeIdsForIngredientIds(ingredientItemIds, List.of());
    }

    /**
     * As {@link #parentRecipeIdsForIngredientIds(Set)}, additionally traversing {@code extraRecipes} (the bundled
     * Mystic Forge recipes, which are not part of the ordinary graph and carry stable negative ids). The extras are
     * indexed per call; the shared graph is never mutated.
     */
    public Set<Integer> parentRecipeIdsForIngredientIds(Set<Integer> ingredientItemIds, Collection<Recipe> extraRecipes) {
        Map<Integer, List<Recipe>> extraByIngredient = new HashMap<>();
        for (Recipe recipe : extraRecipes) {
            for (Ingredient ingredient : recipe.ingredients) {
                extraByIngredient.computeIfAbsent(ingredient.itemId, k -> new ArrayList<>()).add(recipe);
            }
        }
        Set<Integer> visitedItems = new HashSet<>();
        Set<Integer> matchedRecipes = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>(ingredientItemIds);
        while (!pending.isEmpty()) {
            int itemId = pending.removeFirst();
            if (!visitedItems.add(itemId)) continue;
            for (List<Recipe> consumers : List.of(recipesByIngredient.getOrDefault(itemId, List.of()),
                    extraByIngredient.getOrDefault(itemId, List.of()))) {
                for (Recipe recipe : consumers) {
                    matchedRecipes.add(recipe.recipeId);
                    if (!visitedItems.contains(recipe.outputItemId)) pending.addLast(recipe.outputItemId);
                }
            }
        }
        return Collections.unmodifiableSet(matchedRecipes);
    }

    public Set<Integer> parentRecipeIdsForIngredientName(String searchText) {
        return parentRecipeIdsForIngredientName(searchText, List.of());
    }

    /** Name/ID search that also reaches the given extra (Mystic Forge) recipes, directly and as parents. */
    public Set<Integer> parentRecipeIdsForIngredientName(String searchText, Collection<Recipe> extraRecipes) {
        if (searchText == null || searchText.isBlank()) return Set.of();
        String needle = searchText.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return Set.of();
        Set<Integer> seedItems = new HashSet<>();
        itemNames.forEach((itemId, name) -> {
            if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) seedItems.add(itemId);
        });
        if (needle.length() <= 10 && needle.chars().allMatch(c -> c >= '0' && c <= '9')) {
            long id = Long.parseLong(needle);
            if (id > 0 && id <= Integer.MAX_VALUE) seedItems.add((int) id);
        }
        Set<Integer> matched = new HashSet<>(parentRecipeIdsForIngredientIds(seedItems, extraRecipes));
        // A currency name finds the recipes that consume that currency and the verified recipes that produce it
        // (no item is named "Research Note"), so the acquisition path is inspectable from a currency search.
        KnownCurrencies.names().forEach((currencyId, name) -> {
            if (!name.toLowerCase(Locale.ROOT).contains(needle)) return;
            int producer = CurrencyProduction.forCurrency(currencyId).map(CurrencyProduction::itemId).orElse(-1);
            for (Recipe recipe : concat(recipes, extraRecipes)) {
                if (recipe.outputItemId == producer
                        || recipe.currencies.stream().anyMatch(c -> c.currencyId() == currencyId)) {
                    matched.add(recipe.recipeId);
                }
            }
        });
        return Collections.unmodifiableSet(matched);
    }

    private static List<Recipe> concat(List<Recipe> first, Collection<Recipe> second) {
        if (second.isEmpty()) return first;
        List<Recipe> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Map<Integer, List<Recipe>> freeze(Map<Integer, List<Recipe>> recipes) {
        Map<Integer, List<Recipe>> frozen = new HashMap<>();
        recipes.forEach((itemId, producers) -> frozen.put(itemId, List.copyOf(producers)));
        return Collections.unmodifiableMap(frozen);
    }
}
