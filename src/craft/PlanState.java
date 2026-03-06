package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlanState {

    public final Map<Integer, Integer> inventory;
    public final Map<Integer, Integer> missingToBuy = new HashMap<>();
    public final Set<Integer> visiting = new HashSet<>();
    public final Map<Integer, Integer> dailyLeft = new HashMap<>();

    public int buyCostCopper = 0;

    public PlanState(Map<Integer, Integer> baseInventory) {
        this.inventory = new HashMap<>(baseInventory);
    }

    public PlanState(PlanState other) {
        this.inventory = new HashMap<>(other.inventory);
        this.missingToBuy.putAll(other.missingToBuy);
        this.visiting.addAll(other.visiting);
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