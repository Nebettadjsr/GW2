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

    /**
     * Soulbound-and-usable owned quantity, per owning character (DOMAIN_SPEC.md section 2.2.1's
     * coordinated multi-character planning / STORY-DOM-014). Usable only when the craft step
     * consuming it is assigned to that exact character - unlike {@link #boundInventory}, which
     * (for callers that populate it) is usable by whichever character performs the step, and
     * {@link #inventory}, which is shared account-wide. Empty for callers that only ever
     * populate {@link #boundInventory} directly (today's single-character call path), so
     * existing behavior is unchanged for them.
     */
    public final Map<String, Map<Integer, Integer>> characterBoundInventory;

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
        this(sellableInventory, boundInventory, Map.of());
    }

    /**
     * @param sellableInventory       ordinary unbound/tradable owned quantity, shared account-wide.
     * @param accountBoundInventory   account-bound owned quantity; shared account-wide, no TP
     *                                opportunity cost, consumed before {@code sellableInventory}.
     * @param characterBoundInventory soulbound-and-usable owned quantity, keyed by owning
     *                                character name; usable only by a craft step assigned to
     *                                that exact character (see {@link #characterBoundInventory}).
     */
    public PlanState(Map<Integer, Integer> sellableInventory,
                      Map<Integer, Integer> accountBoundInventory,
                      Map<String, Map<Integer, Integer>> characterBoundInventory) {
        this.inventory = new HashMap<>(sellableInventory);
        this.boundInventory = new HashMap<>(accountBoundInventory);
        this.characterBoundInventory = new HashMap<>();
        for (var e : characterBoundInventory.entrySet()) {
            this.characterBoundInventory.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        this.missingToBuy = new HashMap<>();
        this.visiting = new HashSet<>();
        this.dailyLeft = new HashMap<>();
    }

    public PlanState(PlanState other) {
        this.inventory = new HashMap<>();
        this.boundInventory = new HashMap<>();
        this.characterBoundInventory = new HashMap<>();
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

        this.characterBoundInventory.clear();
        for (var e : other.characterBoundInventory.entrySet()) {
            this.characterBoundInventory.put(e.getKey(), new HashMap<>(e.getValue()));
        }

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
        return consumeInventoryWithBinding(itemId, qtyWanted, null);
    }

    /**
     * As {@link #consumeInventoryWithBinding(int, int)}, but consumes {@code assignedCharacter}'s
     * entry in {@link #characterBoundInventory} first (no TP opportunity cost, since soulbound
     * items cannot be sold either way) before falling back to the shared
     * {@link #boundInventory}/{@link #inventory} pools. {@code assignedCharacter} identifies
     * which character is executing the craft step consuming this ingredient (DOMAIN_SPEC.md
     * section 2.2.1); {@code null} skips the per-character pool entirely (used by the
     * single-character/legacy path and by any consumption not attributable to one character).
     */
    public InventoryConsumption consumeInventoryWithBinding(int itemId, int qtyWanted, String assignedCharacter) {
        int usedCharacterBound = 0;
        if (assignedCharacter != null) {
            Map<Integer, Integer> ownerStock = characterBoundInventory.get(assignedCharacter);
            if (ownerStock != null) {
                int have = ownerStock.getOrDefault(itemId, 0);
                usedCharacterBound = Math.min(have, qtyWanted);
                if (usedCharacterBound > 0) {
                    int left = have - usedCharacterBound;
                    if (left > 0) ownerStock.put(itemId, left);
                    else ownerStock.remove(itemId);
                }
            }
        }

        int usedAccountBound = consumeBoundInventory(itemId, qtyWanted - usedCharacterBound);
        int usedSellable = consumeInventory(itemId, qtyWanted - usedCharacterBound - usedAccountBound);
        return new InventoryConsumption(usedCharacterBound + usedAccountBound, usedSellable);
    }

    public record InventoryConsumption(int usedBound, int usedSellable) {
        public int total() {
            return usedBound + usedSellable;
        }
    }
}