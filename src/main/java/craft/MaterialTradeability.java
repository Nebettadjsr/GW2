package craft;

import java.util.Set;

/**
 * Which items a calculation's inputs classify as <em>not</em> tradeable on the Trading Post
 * (DOMAIN_SPEC.md section 2.1.1 / UD-009).
 *
 * <p>This is a fact about items, supplied by the adapter that loaded the calculation's data, in the
 * same way {@link PriceQuote}s are. It is deliberately <em>not</em> derived from price data: a
 * normally tradeable item whose Trading Post quote is missing or zero is not classified here, and
 * stays {@link BlockedReason#PRICE_UNAVAILABLE} exactly as before (DOMAIN_SPEC.md section 21).
 * Nothing about icons, images or display metadata takes part in the classification either.
 *
 * <p>An instance only ever asserts nontradeability for the items it names. {@link #noneKnown()} - the
 * value every caller that supplies no classification gets - names none, so no path is restricted by
 * it and such a caller's results are unchanged.
 *
 * <p>Whether a named item's nontradeability actually restricts a path is a separate question, decided
 * by {@link CraftingSettings#allowNonTradeableMaterials} and asked through
 * {@link PlannerContext#excludesNonTradeableMaterial(int)}.
 */
public final class MaterialTradeability {

    private static final MaterialTradeability NONE_KNOWN = new MaterialTradeability(Set.of());

    private final Set<Integer> nonTradeableItemIds;

    private MaterialTradeability(Set<Integer> nonTradeableItemIds) {
        this.nonTradeableItemIds = nonTradeableItemIds;
    }

    /** No item is classified as non-Trading-Post, so this classification restricts nothing. */
    public static MaterialTradeability noneKnown() {
        return NONE_KNOWN;
    }

    /**
     * The classification naming exactly {@code nonTradeableItemIds} as non-Trading-Post items. The
     * set is copied, so a later change to the caller's collection cannot change a running
     * calculation.
     */
    public static MaterialTradeability ofNonTradeableItems(Set<Integer> nonTradeableItemIds) {
        if (nonTradeableItemIds == null || nonTradeableItemIds.isEmpty()) return NONE_KNOWN;
        return new MaterialTradeability(Set.copyOf(nonTradeableItemIds));
    }

    /** True only for an item this classification names; never a guess for an unnamed one. */
    public boolean isNonTradeable(int itemId) {
        return nonTradeableItemIds.contains(itemId);
    }

    /** The items named, for a caller that reports what a calculation was given. */
    public Set<Integer> nonTradeableItemIds() {
        return nonTradeableItemIds;
    }
}
