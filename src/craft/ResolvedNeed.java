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
}