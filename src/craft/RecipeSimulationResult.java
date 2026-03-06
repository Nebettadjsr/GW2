package craft;

import java.util.HashMap;
import java.util.Map;

public class RecipeSimulationResult {

    private final int recipeId;
    private final int outputItemId;

    private int craftCount;

    private ResolvedNeed firstCraft;
    private ResolvedNeed lastCraft;

    private int buyCostTotal;
    private int opportunityCostTotal;

    private final Map<Integer, Integer> totalMissingToBuy = new HashMap<>();

    public RecipeSimulationResult(int recipeId, int outputItemId) {
        this.recipeId = recipeId;
        this.outputItemId = outputItemId;
    }

    public int getRecipeId() {
        return recipeId;
    }

    public int getOutputItemId() {
        return outputItemId;
    }

    public int getCraftCount() {
        return craftCount;
    }

    public void incrementCraftCount() {
        this.craftCount++;
    }

    public ResolvedNeed getFirstCraft() {
        return firstCraft;
    }

    public void setFirstCraft(ResolvedNeed firstCraft) {
        this.firstCraft = firstCraft;
    }

    public ResolvedNeed getLastCraft() {
        return lastCraft;
    }

    public void setLastCraft(ResolvedNeed lastCraft) {
        this.lastCraft = lastCraft;
    }

    public int getBuyCostTotal() {
        return buyCostTotal;
    }

    public void addBuyCost(int value) {
        buyCostTotal += value;
    }

    public int getOpportunityCostTotal() {
        return opportunityCostTotal;
    }

    public void addOpportunityCost(int value) {
        opportunityCostTotal += value;
    }

    public Map<Integer, Integer> getTotalMissingToBuy() {
        return totalMissingToBuy;
    }

    public void mergeMissing(Map<Integer, Integer> missing) {
        for (var e : missing.entrySet()) {
            totalMissingToBuy.merge(e.getKey(), e.getValue(), Integer::sum);
        }
    }
}