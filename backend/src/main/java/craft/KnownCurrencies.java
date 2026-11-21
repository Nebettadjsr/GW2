package craft;

import java.util.Map;

/** Labels for the wallet currencies this application recognises; any other id keeps its exact number. */
public final class KnownCurrencies {

    private static final Map<Integer, String> NAMES = Map.of(
            2, "Karma", 23, "Spirit Shards", 61, "Research Note", 50, "Festival Token", 78, "Fine Rift Essence",
            79, "Rare Rift Essence", 80, "Masterwork Rift Essence");

    private KnownCurrencies() {}

    public static String name(int currencyId) {
        return NAMES.getOrDefault(currencyId, "Currency " + currencyId);
    }

    public static Map<Integer, String> names() {
        return NAMES;
    }
}
