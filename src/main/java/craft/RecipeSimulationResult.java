package craft;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecipeSimulationResult {

    private final int recipeId;
    private final int outputItemId;

    private int craftCount;

    private ResolvedNeed firstCraft;
    private ResolvedNeed lastCraft;
    private List<ResolvedNeed> acceptedCrafts;

    private int buyCostTotal;
    private int opportunityCostTotal;

    private BlockedReason blockedReason = BlockedReason.NONE;

    /**
     * The craft attempt that ended the last phase without being accepted, paired with
     * {@link #blockedReason} and cleared the same way (STORY-DOM-020). Its state changes were
     * rolled back; it is kept only so a blocked first craft can still be explained.
     */
    private ResolvedNeed blockedAttempt;

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

    /** Accepted traced root attempts, populated only by an explanation simulation. */
    public List<ResolvedNeed> getAcceptedCrafts() {
        return acceptedCrafts == null ? List.of() : List.copyOf(acceptedCrafts);
    }

    void addAcceptedCraft(ResolvedNeed craft) {
        if (acceptedCrafts == null) acceptedCrafts = new ArrayList<>();
        acceptedCrafts.add(craft);
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

    public BlockedReason getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(BlockedReason blockedReason) {
        this.blockedReason = blockedReason;
    }

    /** @see #blockedAttempt */
    public ResolvedNeed getBlockedAttempt() {
        return blockedAttempt;
    }

    public void setBlockedAttempt(ResolvedNeed blockedAttempt) {
        this.blockedAttempt = blockedAttempt;
    }
}
