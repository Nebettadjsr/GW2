package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutable planning state for one recipe's simulation: what is still owned, what must still be
 * bought, which items are currently being resolved, and the daily-craft allowance left.
 *
 * <h2>Speculation without copying (STORY-PERF-001 / UD-005)</h2>
 * The planner is speculative: it tries a craft, compares it against buying, tries one eligible
 * character after another, and keeps only the winner. That used to be expressed by deep-copying
 * the whole state before each attempt and copying the winner back - over four million full copies
 * of ~800 inventory entries for a single All-scope page load, which profiling showed to be the
 * dominant cost of the page.
 *
 * <p>The same speculation is now expressed with an undo journal. Every mutation records the
 * previous value of the slot it touches, so {@link #mark()} plus {@link #rollbackTo(int)} restores
 * an earlier state in time proportional to the number of changes actually made, not to the size of
 * the inventory. {@link #captureDelta(int)}/{@link #applyDelta(Delta)} cover the one case that
 * needs a winner chosen among several speculative attempts. Nothing about which quantities are
 * consumed, in what order, or at what cost changed: the journal only records and reverses the same
 * writes the copying version performed on its throwaway copies.
 *
 * <p>Not thread-safe, by design: each recipe's simulation owns its own instance.
 */
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

    // ---------------- undo journal ----------------

    private static final int SELLABLE = 0;
    private static final int ACCOUNT_BOUND = 1;
    private static final int CHARACTER_BOUND = 2;
    private static final int MISSING = 3;
    private static final int DAILY = 4;
    private static final int VISITING = 5;
    private static final int BUY_COST = 6;

    /** Journalled "this slot had no value before", distinguishable from every real quantity. */
    private static final int ABSENT = Integer.MIN_VALUE;

    private int[] journalKind = new int[64];
    private int[] journalKey = new int[64];
    private int[] journalOldValue = new int[64];
    private String[] journalOwner = new String[64];
    private int journalSize = 0;

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

    /**
     * Independent copy of {@code other}'s current values, with an empty journal - the copy cannot
     * be rolled back past its own creation, which is exactly what its users want.
     */
    public PlanState(PlanState other) {
        this.inventory = new HashMap<>(other.inventory);
        this.boundInventory = new HashMap<>(other.boundInventory);
        this.characterBoundInventory = new HashMap<>();
        for (var e : other.characterBoundInventory.entrySet()) {
            this.characterBoundInventory.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        this.missingToBuy = new HashMap<>(other.missingToBuy);
        this.visiting = new HashSet<>(other.visiting);
        this.dailyLeft = new HashMap<>(other.dailyLeft);
        this.buyCostCopper = other.buyCostCopper;
    }

    /**
     * A position in this state's history. Pass it to {@link #rollbackTo(int)} to undo every change
     * made since, or to {@link #commitTo(int)} to make those changes permanent and forget them.
     */
    public int mark() {
        return journalSize;
    }

    /** Undoes every change made since {@code mark}, restoring the exact values held at that point. */
    public void rollbackTo(int mark) {
        for (int i = journalSize - 1; i >= mark; i--) {
            undo(i);
            journalOwner[i] = null;
        }
        journalSize = mark;
    }

    /**
     * Declares every change made since {@code mark} permanent, dropping the undo information for
     * it. Only valid when no caller still holds an older mark on this state - the per-recipe
     * simulation loop, whose committed crafts are never revisited, is the intended user; it keeps
     * the journal from growing across up to 250 accepted craft iterations.
     */
    public void commitTo(int mark) {
        for (int i = mark; i < journalSize; i++) {
            journalOwner[i] = null;
        }
        journalSize = mark;
    }

    /**
     * The net effect of everything changed since {@code mark}, so one speculative attempt can be
     * set aside, other attempts tried from the same starting point, and the winner re-applied via
     * {@link #applyDelta(Delta)} without re-running it.
     */
    public Delta captureDelta(int mark) {
        int count = journalSize - mark;
        int[] kinds = new int[count];
        int[] keys = new int[count];
        int[] values = new int[count];
        String[] owners = new String[count];

        for (int i = 0; i < count; i++) {
            int j = mark + i;
            kinds[i] = journalKind[j];
            keys[i] = journalKey[j];
            owners[i] = journalOwner[j];
            values[i] = currentValue(journalKind[j], journalKey[j], journalOwner[j]);
        }
        return new Delta(kinds, keys, values, owners);
    }

    /**
     * Re-applies a previously {@link #captureDelta(int) captured} net effect. Recorded in this
     * state's journal like any other change, so an enclosing speculation can still undo it.
     */
    public void applyDelta(Delta delta) {
        if (delta == null) return;
        for (int i = 0; i < delta.kinds.length; i++) {
            setValue(delta.kinds[i], delta.keys[i], delta.owners[i], delta.values[i]);
        }
    }

    /** Opaque net effect of a span of changes; see {@link #captureDelta(int)}. */
    public static final class Delta {
        private final int[] kinds;
        private final int[] keys;
        private final int[] values;
        private final String[] owners;

        private Delta(int[] kinds, int[] keys, int[] values, String[] owners) {
            this.kinds = kinds;
            this.keys = keys;
            this.values = values;
            this.owners = owners;
        }
    }

    private void record(int kind, int key, String owner, int oldValue) {
        if (journalSize == journalKind.length) {
            int grown = journalKind.length * 2;
            journalKind = java.util.Arrays.copyOf(journalKind, grown);
            journalKey = java.util.Arrays.copyOf(journalKey, grown);
            journalOldValue = java.util.Arrays.copyOf(journalOldValue, grown);
            journalOwner = java.util.Arrays.copyOf(journalOwner, grown);
        }
        journalKind[journalSize] = kind;
        journalKey[journalSize] = key;
        journalOwner[journalSize] = owner;
        journalOldValue[journalSize] = oldValue;
        journalSize++;
    }

    private void undo(int index) {
        writeRaw(journalKind[index], journalKey[index], journalOwner[index], journalOldValue[index]);
    }

    private void setValue(int kind, int key, String owner, int value) {
        record(kind, key, owner, currentValue(kind, key, owner));
        writeRaw(kind, key, owner, value);
    }

    private int currentValue(int kind, int key, String owner) {
        return switch (kind) {
            case SELLABLE -> valueOrAbsent(inventory, key);
            case ACCOUNT_BOUND -> valueOrAbsent(boundInventory, key);
            case CHARACTER_BOUND -> {
                Map<Integer, Integer> stock = characterBoundInventory.get(owner);
                yield stock == null ? ABSENT : valueOrAbsent(stock, key);
            }
            case MISSING -> valueOrAbsent(missingToBuy, key);
            case DAILY -> valueOrAbsent(dailyLeft, key);
            case VISITING -> visiting.contains(key) ? 1 : ABSENT;
            case BUY_COST -> buyCostCopper;
            default -> throw new IllegalStateException("unknown journal kind " + kind);
        };
    }

    private void writeRaw(int kind, int key, String owner, int value) {
        switch (kind) {
            case SELLABLE -> writeOrRemove(inventory, key, value);
            case ACCOUNT_BOUND -> writeOrRemove(boundInventory, key, value);
            case CHARACTER_BOUND -> {
                Map<Integer, Integer> stock = characterBoundInventory.get(owner);
                if (stock != null) writeOrRemove(stock, key, value);
            }
            case MISSING -> writeOrRemove(missingToBuy, key, value);
            case DAILY -> writeOrRemove(dailyLeft, key, value);
            case VISITING -> {
                if (value == ABSENT) visiting.remove(key);
                else visiting.add(key);
            }
            case BUY_COST -> buyCostCopper = value;
            default -> throw new IllegalStateException("unknown journal kind " + kind);
        }
    }

    private static int valueOrAbsent(Map<Integer, Integer> map, int key) {
        Integer v = map.get(key);
        return v == null ? ABSENT : v;
    }

    private static void writeOrRemove(Map<Integer, Integer> map, int key, int value) {
        if (value == ABSENT) map.remove(key);
        else map.put(key, value);
    }

    // ---------------- journalled mutations ----------------

    /** Adds {@code qty} to what still has to be bought for {@code itemId}. */
    public void addMissingToBuy(int itemId, int qty) {
        int current = valueOrAbsent(missingToBuy, itemId);
        setValue(MISSING, itemId, null, current == ABSENT ? qty : current + qty);
    }

    /** Adds {@code delta} copper to the running cash cost of this plan. */
    public void addBuyCost(int delta) {
        setValue(BUY_COST, 0, null, buyCostCopper + delta);
    }

    /** True while {@code itemId} is being resolved higher up the current recursion (cycle guard). */
    public boolean isVisiting(int itemId) {
        return visiting.contains(itemId);
    }

    public void beginVisiting(int itemId) {
        setValue(VISITING, itemId, null, 1);
    }

    public void endVisiting(int itemId) {
        setValue(VISITING, itemId, null, ABSENT);
    }

    /** Daily-craft operations still available for {@code itemId}, defaulting to {@code ifAbsent}. */
    public int dailyLeft(int itemId, int ifAbsent) {
        return dailyLeft.getOrDefault(itemId, ifAbsent);
    }

    public void setDailyLeft(int itemId, int value) {
        setValue(DAILY, itemId, null, value);
    }

    public int consumeInventory(int itemId, int qtyWanted) {
        int have = inventory.getOrDefault(itemId, 0);
        int used = Math.min(have, qtyWanted);

        if (used <= 0) {
            return 0;
        }

        int left = have - used;
        setValue(SELLABLE, itemId, null, left > 0 ? left : ABSENT);

        return used;
    }

    private int consumeBoundInventory(int itemId, int qtyWanted) {
        int have = boundInventory.getOrDefault(itemId, 0);
        int used = Math.min(have, qtyWanted);

        if (used <= 0) {
            return 0;
        }

        int left = have - used;
        setValue(ACCOUNT_BOUND, itemId, null, left > 0 ? left : ABSENT);

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
                    setValue(CHARACTER_BOUND, itemId, assignedCharacter, left > 0 ? left : ABSENT);
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
