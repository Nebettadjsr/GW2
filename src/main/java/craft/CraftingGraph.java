package craft;

import java.util.*;

public class CraftingGraph {

    private final List<Recipe> recipes;
    private final Map<Integer, List<Recipe>> recipesByOutput;

    public CraftingGraph(List<Recipe> recipes) {
        this.recipes = recipes;
        this.recipesByOutput = new HashMap<>();

        for (Recipe r : recipes) {
            recipesByOutput
                    .computeIfAbsent(r.outputItemId, k -> new ArrayList<>())
                    .add(r);
        }
    }

    public List<Recipe> getRecipes() {
        return recipes;
    }

    public Map<Integer, List<Recipe>> getRecipesByOutput() {
        return recipesByOutput;
    }
}