package craft;

import java.util.List;

/**
 * The small verified set of fixed-price vendor purchases Mystic Forge plans need (never a general vendor engine).
 * Values mirror {@code backend/tools/mystic-forge/source/verified-facts.json} (GW2 Wiki, checked 2026-11-19; a test
 * keeps the two in step). Spirit Shards are an exact, unpriced wallet currency (no gold rate is invented); Wine is
 * a fixed copper price that is real buy cash under the shared budget.
 */
public final class VendorAcquisitions {

    /** Synthetic ids live below the Forge identity range so they never collide with an ArenaNet or Forge recipe id. */
    public static final int ID_BASE = 1_000_000_000;
    public static final int SPIRIT_SHARD = 23;

    private static final List<Recipe> RECIPES = List.of(
            Recipe.vendor(-(ID_BASE + 20796), 20796, 10, List.of(new CurrencyRequirement(SPIRIT_SHARD, 1)), 0),
            Recipe.vendor(-(ID_BASE + 20799), 20799, 5, List.of(new CurrencyRequirement(SPIRIT_SHARD, 3)), 0),
            Recipe.vendor(-(ID_BASE + 20852), 20852, 1, List.of(new CurrencyRequirement(SPIRIT_SHARD, 50)), 0),
            Recipe.vendor(-(ID_BASE + 19663), 19663, 1, List.of(), 2504));

    private VendorAcquisitions() { }

    public static List<Recipe> recipes() {
        return RECIPES;
    }

    /** Stable text of every vendor fact for calculation-input/cache identity. */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder("vendor-v1");
        for (Recipe r : RECIPES) {
            sb.append('|').append(r.outputItemId).append('x').append(r.outputCount).append('c').append(r.vendorCopper);
            r.currencies.forEach(c -> sb.append('s').append(c.currencyId()).append(':').append(c.count()));
        }
        return sb.toString();
    }
}
