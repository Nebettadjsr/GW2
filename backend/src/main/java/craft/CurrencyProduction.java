package craft;

import java.util.Map;
import java.util.Optional;

/**
 * Verified, fixed production of a wallet currency by crafting (ArenaNet "Easier Acquisition of Research Notes"):
 * the account-bound container item Peer Reviewed Research (item 104934, produced by recipes 15120 and 15131)
 * is opened immediately and awards exactly 150 Research Notes (currency 61), with no random rating.
 * This is a deliberate small fixed rule at the external-data boundary, not parsed from item descriptions and
 * not a general resource framework. The yield is a quantity, never a gold conversion rate.
 *
 * @param itemId   crafted output item that is immediately converted into the currency
 * @param yield    currency units one such item awards
 */
public record CurrencyProduction(int itemId, int yield) {

    public static final int RESEARCH_NOTE_CURRENCY_ID = 61;
    public static final int PEER_REVIEWED_RESEARCH_ITEM_ID = 104934;

    private static final Map<Integer, CurrencyProduction> BY_CURRENCY = Map.of(
            RESEARCH_NOTE_CURRENCY_ID, new CurrencyProduction(PEER_REVIEWED_RESEARCH_ITEM_ID, 150));

    public CurrencyProduction {
        if (itemId <= 0 || yield <= 0) throw new IllegalArgumentException("item id and yield must be positive");
    }

    public static Optional<CurrencyProduction> forCurrency(int currencyId) {
        return Optional.ofNullable(BY_CURRENCY.get(currencyId));
    }

    /** Whether this item immediately credits a currency (so it is never an ordinary tradable output). */
    public static boolean isProducerItem(int itemId) {
        return BY_CURRENCY.values().stream().anyMatch(p -> p.itemId == itemId);
    }
}
