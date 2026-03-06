package craft;

public class ResolveResult {

    private final ResolvedNeed root;

    public ResolveResult(ResolvedNeed root) {
        this.root = root;
    }

    public ResolvedNeed getRoot() {
        return root;
    }

    public int getBuyCostCopper() {
        return root.getBuyCostCopper();
    }

    public int getOpportunityCostCopper() {
        return root.getOpportunityCostCopper();
    }

    public int getEffectiveCostCopper() {
        return root.getEffectiveCostCopper();
    }

    public int getQtySatisfied() {
        return root.getQtySatisfied();
    }

    public int getQtyBlocked() {
        return root.getQtyBlocked();
    }
}