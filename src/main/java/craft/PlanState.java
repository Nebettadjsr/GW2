package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlanState {

    public final Map<Integer, Integer> inventory;

    /**
     * Owned quantity that is account-bound or soulbound-and-usable-by-the-selected-character
     * (DOMAIN_SPEC.md section 11.1 / DQ-007). Consumed before {@link #inventory} and never
     * charged Trading-Post opportunity cost. Empty for callers that only ever populate
     * {@link #inventory} (today's production call path), so existing behavior is unchanged.
     */
    public final Map<Integer, Integer> boundInventory;

    public final Map<Integer, Integer> missingToBuy;
    public final Set<Integer> visiting;
    public final Map<Integer, Integer> dailyLeft;

    public int buyCostCopper = 0;

    public PlanState(Map<Integer, Integer> baseInventory) {
        this(baseInventory, Map.of());
    }

    /**
     * @param sellableInventory ordinary unbound/tradable owned quantity, priced exactly as
     *                          {@link #PlanState(Map)} always has.
     * @param boundInventory    account-bound/soulbound-and-usable owned quantity; no TP
     *                          opportunity cost, consumed first.
     */
    public PlanState(Map<Integer, Integer> sellableInventory, Map<Integer, Integer> boundInventory) {
        this.inventory = new HashMap<>(sellableInventory);
        this.boundInventory = new HashMap<>(boundInventory);
        this.missingToBuy = new HashMap<>();
        this.visiting = new HashSet<>();
        this.dailyLeft = new HashMap<>();
    }

    public PlanState(PlanState other) {
        this.inventory = new HashMap<>();
        this.boundInventory = new HashMap<>();
        this.missingToBuy = new HashMap<>();
        this.visiting = new HashSet<>();
        this.dailyLeft = new HashMap<>();
        copyFrom(other);
    }

    public void copyFrom(PlanState other) {
        this.inventory.clear();
        this.inventory.putAll(other.inventory);

        this.boundInventory.clear();
        this.boundInventory.putAll(other.boundInventory);

        this.missingToBuy.clear();
        this.missingToBuy.putAll(other.missingToBuy);

        this.visiting.clear();
        this.visiting.addAll(other.visiting);

        this.dailyLeft.clear();
        this.dailyLeft.putAll(other.dailyLeft);

        this.buyCostCopper = other.buyCostCopper;
    }

    public int consumeInventory(int itemId, int qtyWanted) {
        int have = inventory.getOrDefault(itemId, 0);
        int used = Math.min(have, qtyWanted);

        if (used <= 0) {
            return 0;
        }

        int left = have - used;
        if (left > 0) {
            inventory.put(itemId, left);
        } else {
            inventory.remove(itemId);
        }

        return used;
    }

    private int consumeBoundInventory(int itemId, int qtyWanted) {
        int have = boundInventory.getOrDefault(itemId, 0);
        int used = Math.min(have, qtyWanted);

        if (used <= 0) {
            return 0;
        }

        int left = have - used;
        if (left > 0) {
            boundInventory.put(itemId, left);
        } else {
            boundInventory.remove(itemId);
        }

        return used;
    }

    /**
     * Consumes {@link #boundInventory} first (no TP opportunity cost), then falls back to
     * ordinary {@link #inventory} for any remaining wanted quantity. For callers that never
     * populate {@link #boundInventory}, {@code usedBound()} is always 0 and {@code usedSellable()}
     * equals what {@link #consumeInventory(int, int)} alone would have returned.
     */
    public InventoryConsumption consumeInventoryWithBinding(int itemId, int qtyWanted) {
        int usedBound = consumeBoundInventory(itemId, qtyWanted);
        int usedSellable = consumeInventory(itemId, qtyWanted - usedBound);
        return new InventoryConsumption(usedBound, usedSellable);
    }

    public record InventoryConsumption(int usedBound, int usedSellable) {
        public int total() {
            return usedBound + usedSellable;
        }
    }
}