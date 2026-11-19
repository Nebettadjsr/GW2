package craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ResolvedNeed {

    private final int itemId;
    private final int qtyRequested;

    private int qtyFromInventory;
    private int qtyCrafted;
    private int qtyBought;
    private int qtyBlocked;

    private int buyCostCopper;
    private int opportunityCostCopper;

    /** An owned sellable quantity here (or below) could not be valued at all (DOMAIN_SPEC.md 11.2). */
    private boolean unvaluedOwnedInput;
    private boolean usesExpectedYield;
    private boolean spendsUnpricedCurrency;
    private final java.util.Set<Integer> unpricedCurrencyIds = new java.util.TreeSet<>();
    /** Research Notes paid from the captured wallet here (or below), valued by {@link #noteEstimate}. */
    private int walletNoteUnits;
    private ResearchNoteEstimate noteEstimate;

    /** Vendor batches bought here (0 when this node was not a vendor acquisition) and the units they produced. */
    private int vendorBatches;
    private int vendorProduced;

    private final List<CurrencySourcing> currencySourcing = new ArrayList<>();

    private AcquisitionMode mode = AcquisitionMode.BLOCKED;
    private BlockedReason blockedReason = BlockedReason.NONE;

    private final List<ResolvedNeed> children = new ArrayList<>();

    /**
     * Trace-only explanation facts (STORY-DOM-020), recorded by {@link CraftingResolver} only when
     * that resolver was constructed with tracing enabled. It stays {@code null} on every node of an
     * ordinary table calculation, so no semantic tree is constructed for table rows.
     */
    private NodeTrace trace;

    public ResolvedNeed(int itemId, int qtyRequested) {
        this.itemId = itemId;
        this.qtyRequested = qtyRequested;
    }

    public int getItemId() {
        return itemId;
    }

    public int getQtyRequested() {
        return qtyRequested;
    }

    public int getQtyFromInventory() {
        return qtyFromInventory;
    }

    public void setQtyFromInventory(int qtyFromInventory) {
        this.qtyFromInventory = qtyFromInventory;
    }

    public int getQtyCrafted() {
        return qtyCrafted;
    }

    public void setQtyCrafted(int qtyCrafted) {
        this.qtyCrafted = qtyCrafted;
    }

    public int getQtyBought() {
        return qtyBought;
    }

    public void setQtyBought(int qtyBought) {
        this.qtyBought = qtyBought;
    }

    public int getQtyBlocked() {
        return qtyBlocked;
    }

    public void setQtyBlocked(int qtyBlocked) {
        this.qtyBlocked = qtyBlocked;
    }

    public int getBuyCostCopper() {
        return buyCostCopper;
    }

    public void setBuyCostCopper(int buyCostCopper) {
        this.buyCostCopper = buyCostCopper;
    }

    public int getOpportunityCostCopper() {
        return opportunityCostCopper;
    }

    public void setOpportunityCostCopper(int opportunityCostCopper) {
        this.opportunityCostCopper = opportunityCostCopper;
    }

    /** Exact effective cost; may exceed the int range, so comparisons use this. */
    public long effectiveCostCopper() {
        return (long) buyCostCopper + opportunityCostCopper + walletNoteCostCopper();
    }

    public ResearchNoteEstimate getNoteEstimate() {
        return noteEstimate;
    }

    public int getWalletNoteUnits() {
        return walletNoteUnits;
    }

    /** Records wallet-paid Notes that are valued by the estimate (never newly produced or surplus-reused ones). */
    public void addWalletNotes(int units, ResearchNoteEstimate estimate) {
        if (units <= 0) return;
        this.walletNoteUnits = Math.addExact(this.walletNoteUnits, units);
        this.noteEstimate = estimate;
    }

    /** Copies the wallet-Note facts of another node (the root of a chosen craft route). */
    public void absorbWalletNotes(ResolvedNeed other) {
        if (other.walletNoteUnits > 0) addWalletNotes(other.walletNoteUnits, other.noteEstimate);
    }

    /** Whole copper of the estimated wallet Notes here, rounded up once over the aggregate. */
    public int walletNoteCostCopper() {
        return walletNoteUnits == 0 || noteEstimate == null ? 0 : noteEstimate.costCopper(walletNoteUnits);
    }

    /** @throws ArithmeticException when the effective cost is outside the supported int range */
    public int getEffectiveCostCopper() {
        return Math.addExact(Math.addExact(buyCostCopper, opportunityCostCopper), walletNoteCostCopper());
    }

    public int getQtySatisfied() {
        return qtyFromInventory + qtyCrafted + qtyBought;
    }

    public AcquisitionMode getMode() {
        return mode;
    }

    public void setMode(AcquisitionMode mode) {
        this.mode = mode;
    }

    public BlockedReason getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(BlockedReason blockedReason) {
        this.blockedReason = blockedReason;
    }

    public List<ResolvedNeed> getChildren() {
        return children;
    }

    public void addChild(ResolvedNeed child) {
        this.children.add(child);
        this.usesExpectedYield |= child.usesExpectedYield;
    }

    public void markVendorAcquisition(int batches, int produced) {
        this.vendorBatches = batches;
        this.vendorProduced = produced;
    }

    private int yieldBatches;
    private int yieldProducedGross;
    private int yieldReusedSeed;

    /**
     * Guaranteed (minimum-yield) physical accounting of a variable Forge step: {@code batches} sequential crafts
     * produced {@code gross} units, of which {@code reusedSeed} were consumed again by a later batch of the same step.
     * Net = gross - reusedSeed. Never includes fractional expected output.
     */
    public void markYieldAccounting(int batches, int gross, int reusedSeed) {
        this.yieldBatches = batches;
        this.yieldProducedGross = gross;
        this.yieldReusedSeed = reusedSeed;
    }
    public int getYieldBatches() { return yieldBatches; }
    public int getYieldProducedGross() { return yieldProducedGross; }
    public int getYieldReusedSeed() { return yieldReusedSeed; }
    public int getYieldProducedNet() { return yieldProducedGross - yieldReusedSeed; }

    public int getVendorBatches() { return vendorBatches; }
    public int getVendorProduced() { return vendorProduced; }

    /** Accumulates vendor-bought units ([0]) and their exact cash ([1]) per item over this need and its descendants. */
    public void accumulateVendorPurchases(Map<Integer, int[]> purchases) {
        if (vendorBatches > 0) {
            int[] t = purchases.computeIfAbsent(itemId, k -> new int[2]);
            t[0] = Math.addExact(t[0], vendorProduced);
            t[1] = Math.addExact(t[1], buyCostCopper);
        }
        for (ResolvedNeed child : children) child.accumulateVendorPurchases(purchases);
    }

    /** Accumulates every purchase represented by this resolved need and its descendants. */
    public void accumulateBoughtItems(Map<Integer, Integer> purchases) {
        // A vendor purchase is whole batches: the shopping quantity is every unit bought, not only what was used here.
        if (vendorBatches > 0) purchases.merge(itemId, vendorProduced, Integer::sum);
        else if (qtyBought > 0) purchases.merge(itemId, qtyBought, Integer::sum);
        for (ResolvedNeed child : children) {
            child.accumulateBoughtItems(purchases);
        }
    }

    public void determineMode() {
        int usedSources = 0;
        if (qtyFromInventory > 0) usedSources++;
        if (qtyCrafted > 0) usedSources++;
        if (qtyBought > 0) usedSources++;

        if (getQtySatisfied() == 0) {
            mode = AcquisitionMode.BLOCKED;
            return;
        }

        if (qtyBlocked > 0 || usedSources > 1) {
            mode = AcquisitionMode.MIXED;
            return;
        }

        if (qtyFromInventory > 0) {
            mode = AcquisitionMode.INVENTORY;
        } else if (qtyCrafted > 0) {
            mode = AcquisitionMode.CRAFT;
        } else if (qtyBought > 0) {
            mode = AcquisitionMode.BUY;
        } else {
            mode = AcquisitionMode.BLOCKED;
        }
    }

    /** True when this step or a nested one is a variable-output Forge route (estimated, never guaranteed output). */
    public boolean usesExpectedYield() { return usesExpectedYield; }

    public void markUsesExpectedYield() { this.usesExpectedYield = true; }

    public void addCostsFromChild(ResolvedNeed child) {
        this.buyCostCopper = Math.addExact(this.buyCostCopper, child.getBuyCostCopper());
        this.opportunityCostCopper = Math.addExact(this.opportunityCostCopper, child.getOpportunityCostCopper());
        this.unvaluedOwnedInput |= child.unvaluedOwnedInput;
        this.spendsUnpricedCurrency |= child.spendsUnpricedCurrency;
        this.unpricedCurrencyIds.addAll(child.unpricedCurrencyIds);
        if (child.walletNoteUnits > 0) addWalletNotes(child.walletNoteUnits, child.noteEstimate);
    }

    /**
     * True when this route (itself or any nested step) spends currency that has no gold valuation, so its copper
     * cost is not a complete cost and must not be ranked against fully valued routes.
     */
    public boolean spendsUnpricedCurrency() {
        return spendsUnpricedCurrency;
    }

    public void setSpendsUnpricedCurrency(boolean spends) {
        this.spendsUnpricedCurrency = spends;
    }

    /** Marks one specific currency (by currency id) as spent without a gold valuation. */
    public void addUnpricedCurrency(int currencyId) {
        this.spendsUnpricedCurrency = true;
        this.unpricedCurrencyIds.add(currencyId);
    }

    /** The currency ids whose consumption in this route (itself or nested) has no gold valuation. */
    public java.util.Set<Integer> unpricedCurrencyIds() {
        return unpricedCurrencyIds;
    }

    /**
     * How this craft step's currency requirements were sourced, per currency (empty when it needs none).
     * Quantities only; never a gold valuation.
     *
     * @param needed         units the executions of this step consume
     * @param fromWallet     units paid from the account wallet's captured balance (owned, deferred valuation)
     * @param fromProduced   units reused from surplus an earlier production batch of the same plan credited
     * @param crafted        units newly credited by this step's production batches and consumed here
     * @param missing        units that no source could provide (the step is then blocked)
     * @param walletBalance  captured balance before this plan spent anything, or null when unknown
     */
    public record CurrencySourcing(int currencyId, int needed, int fromWallet, int fromProduced, int crafted,
                                   int missing, Integer walletBalance) {}

    public List<CurrencySourcing> getCurrencySourcing() {
        return currencySourcing;
    }

    public void addCurrencySourcing(CurrencySourcing sourcing) {
        currencySourcing.add(sourcing);
    }

    public boolean hasUnvaluedOwnedInput() {
        return unvaluedOwnedInput;
    }

    public void setUnvaluedOwnedInput(boolean unvaluedOwnedInput) {
        this.unvaluedOwnedInput = unvaluedOwnedInput;
    }

    public boolean isFullySatisfied() {
        return qtyBlocked == 0 && getQtySatisfied() >= qtyRequested;
    }

    /**
     * The resolver's recorded explanation facts for this requirement, or {@code null} when the
     * resolution that produced it was not tracing (every ordinary table calculation).
     */
    public NodeTrace getTrace() {
        return trace;
    }

    /** Creates this node's trace record on first use; only a tracing resolver calls this. */
    NodeTrace traceForWrite() {
        if (trace == null) {
            trace = new NodeTrace();
        }
        return trace;
    }

    /**
     * The facts a semantic explanation needs that the economic result itself does not carry
     * (DOMAIN_SPEC.md section 44, ARCHITECTURE.md section 7): which recipe the resolver
     * actually selected for this requirement, how many times it was executed and how much that
     * produced, which character was assigned to it, whether consumed owned quantity had no
     * modellable value, and the craft attempt whose ingredient requirements explain this node.
     *
     * <p>Only the accepted attempt is ever linked here: a speculative craft that loses to buying or
     * to another eligible character is discarded with its {@link ResolvedNeed} and never reachable.
     */
    public static final class NodeTrace {

        private Integer selectedRecipeId;
        private int craftCount;
        private int producedQuantity;
        private String assignedCharacter;
        private boolean unvaluedNonTradable;
        private ResolvedNeed craftAttempt;

        /** The recipe the resolver selected (or attempted) for this requirement; null if none. */
        public Integer getSelectedRecipeId() {
            return selectedRecipeId;
        }

        /** Completed executions of {@link #getSelectedRecipeId()}; 0 when no craft completed. */
        public int getCraftCount() {
            return craftCount;
        }

        /** Output units those executions produced, which may exceed the quantity used here. */
        public int getProducedQuantity() {
            return producedQuantity;
        }

        /** Character assigned to perform the craft, or null (unassigned / individual path). */
        public String getAssignedCharacter() {
            return assignedCharacter;
        }

        /**
         * True when sellable owned quantity was consumed at a unit value of zero, i.e. the domain
         * established a zero economic value it could not model (DOMAIN_SPEC.md section 11.2's
         * {@code UNVALUED_NONTRADEABLE}); such a quantity is not genuinely free.
         */
        public boolean isUnvaluedNonTradable() {
            return unvaluedNonTradable;
        }

        /**
         * The craft attempt explaining this requirement - accepted, or attempted and blocked -
         * whose children are this node's ingredient requirements. Null when no crafting path was
         * selected.
         */
        public ResolvedNeed getCraftAttempt() {
            return craftAttempt;
        }

        void setSelectedRecipeId(Integer selectedRecipeId) {
            this.selectedRecipeId = selectedRecipeId;
        }

        void setCraft(int craftCount, int producedQuantity) {
            this.craftCount = craftCount;
            this.producedQuantity = producedQuantity;
        }

        void setAssignedCharacter(String assignedCharacter) {
            this.assignedCharacter = assignedCharacter;
        }

        void markUnvaluedNonTradable() {
            this.unvaluedNonTradable = true;
        }

        void setCraftAttempt(ResolvedNeed craftAttempt) {
            this.craftAttempt = craftAttempt;
        }
    }
}
