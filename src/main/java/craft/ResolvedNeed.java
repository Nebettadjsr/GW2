package craft;

import java.util.ArrayList;
import java.util.List;

public class ResolvedNeed {

    private final int itemId;
    private final int qtyRequested;

    private int qtyFromInventory;
    private int qtyCrafted;
    private int qtyBought;
    private int qtyBlocked;

    private int buyCostCopper;
    private int opportunityCostCopper;

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

    public int getEffectiveCostCopper() {
        return buyCostCopper + opportunityCostCopper;
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

    public void addCostsFromChild(ResolvedNeed child) {
        this.buyCostCopper += child.getBuyCostCopper();
        this.opportunityCostCopper += child.getOpportunityCostCopper();
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
     * (DOMAIN_SPEC.md section 44, TARGET_ARCHITECTURE.md section 13.3): which recipe the resolver
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