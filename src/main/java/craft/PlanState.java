package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlanState {

    public final Map<Integer, Integer> inventory;
    public final Map<Integer, Integer> missingToBuy;
    public final Set<Integer> visiting;
    public final Map<Integer, Integer> dailyLeft;

    public int buyCostCopper = 0;

    public PlanState(Map<Integer, Integer> baseInventory) {
        this.inventory = new HashMap<>(baseInventory);
        this.missingToBuy = new HashMap<>();
        this.visiting = new HashSet<>();
        this.dailyLeft = new HashMap<>();
    }

    public PlanState(PlanState other) {
        this.inventory = new HashMap<>();
        this.missingToBuy = new HashMap<>();
        this.visiting = new HashSet<>();
        this.dailyLeft = new HashMap<>();
        copyFrom(other);
    }

    public void copyFrom(PlanState other) {
        this.inventory.clear();
        this.inventory.putAll(other.inventory);

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
}