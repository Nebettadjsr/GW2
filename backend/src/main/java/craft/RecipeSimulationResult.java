package craft;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecipeSimulationResult {

    private final int recipeId;
    private final int outputItemId;

    private int craftCount;
    /** Guaranteed-minimum output units credited as reusable self-seed stock and not yet consumed. */
    private int seedCreditOutstanding;
    /** Credited units a later batch consumed as its seed: removed from sellable output exactly once. */
    private int reusedSeedUnits;

    private ResolvedNeed firstCraft;
    private ResolvedNeed lastCraft;
    private List<ResolvedNeed> acceptedCrafts;

    private int buyCostTotal;
    private int opportunityCostTotal;
    private boolean unvaluedOwnedInput;
    private boolean unpricedCurrency;
    private final java.util.Set<Integer> unpricedCurrencyIds = new java.util.TreeSet<>();
    private long walletNoteUnitsTotal;
    private Map<Integer, Integer> currencyTotal = Map.of();
    private Map<Integer, Integer> currencyFirstCraft = Map.of();

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

    int seedCreditOutstanding() { return seedCreditOutstanding; }
    void setSeedCreditOutstanding(int units) { this.seedCreditOutstanding = units; }
    public int getReusedSeedUnits() { return reusedSeedUnits; }
    void addReusedSeedUnits(int units) { this.reusedSeedUnits = Math.addExact(reusedSeedUnits, units); }

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

    public boolean hasUnvaluedOwnedInput() {
        return unvaluedOwnedInput;
    }

    /** True when an accepted craft paid any currency from the wallet (deferred, unpriced valuation). */
    public boolean spendsUnpricedCurrency() {
        return unpricedCurrency;
    }

    /** Research Notes the accepted crafts paid from the captured wallet (valued by the estimate). */
    public long getWalletNoteUnitsTotal() { return walletNoteUnitsTotal; }
    public void addWalletNoteUnits(int units) { walletNoteUnitsTotal = Math.addExact(walletNoteUnitsTotal, units); }

    public void markUnpricedCurrency() {
        unpricedCurrency = true;
    }

    /** Marks the given currencies (by currency id) as spent without a gold valuation. */
    public void markUnpricedCurrencies(java.util.Collection<Integer> currencyIds) {
        unpricedCurrency = true;
        unpricedCurrencyIds.addAll(currencyIds);
    }

    public java.util.Set<Integer> unpricedCurrencyIds() {
        return unpricedCurrencyIds;
    }

    public void markUnvaluedOwnedInput() {
        unvaluedOwnedInput = true;
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

    /** Cumulative currency spent by every accepted craft (wallet pool), by currency id. */
    public Map<Integer, Integer> getCurrencyTotal() { return currencyTotal; }
    public void setCurrencyTotal(Map<Integer, Integer> value) { this.currencyTotal = value; }

    /** Currency spent by the first accepted craft including nested crafts, by currency id. */
    public Map<Integer, Integer> getCurrencyFirstCraft() { return currencyFirstCraft; }
    public void setCurrencyFirstCraft(Map<Integer, Integer> value) { this.currencyFirstCraft = value; }
}
