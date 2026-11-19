package craft;

/**
 * Exact quantity of one currency (for example Research Note, currency 61) a recipe consumes per craft.
 * The currency id lives in its own namespace and is never an item id. The quantity is actual wallet
 * demand and is kept separate from any gold valuation of that currency; an unknown value is not zero.
 */
public record CurrencyRequirement(int currencyId, int count) {
    public CurrencyRequirement {
        if (currencyId <= 0 || count <= 0) throw new IllegalArgumentException("currency id and count must be positive");
    }
}
