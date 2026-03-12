package craft;

import repo.RecipeRepository;

import java.util.*;

public class CraftingGraph {

    private final List<RecipeRepository.Recipe> recipes;
    private final Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput;

    public CraftingGraph(List<RecipeRepository.Recipe> recipes) {
        this.recipes = recipes;
        this.recipesByOutput = new HashMap<>();

        for (RecipeRepository.Recipe r : recipes) {
            recipesByOutput
                    .computeIfAbsent(r.outputItemId, k -> new ArrayList<>())
                    .add(r);
        }
    }

    public List<RecipeRepository.Recipe> getRecipes() {
        return recipes;
    }

    public Map<Integer, List<RecipeRepository.Recipe>> getRecipesByOutput() {
        return recipesByOutput;
    }
}