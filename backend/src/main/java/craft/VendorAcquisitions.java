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
    public static final int MYSTIC_RUNESTONE = 79418;

    private static final List<Recipe> RECIPES = List.of(
            Recipe.vendor(-(ID_BASE + 20796), 20796, 10, List.of(new CurrencyRequirement(SPIRIT_SHARD, 1)), 0),
            Recipe.vendor(-(ID_BASE + 20799), 20799, 5, List.of(new CurrencyRequirement(SPIRIT_SHARD, 3)), 0),
            Recipe.vendor(-(ID_BASE + 20852), 20852, 1, List.of(new CurrencyRequirement(SPIRIT_SHARD, 50)), 0),
            Recipe.vendor(-(ID_BASE + 19663), 19663, 1, List.of(), 2504),
            // Mystic Runestone: merchant purchase price 100 silver (10000c) per unit, NOT its 330c vendor sell value.
            Recipe.vendor(-(ID_BASE + MYSTIC_RUNESTONE), MYSTIC_RUNESTONE, 1, List.of(), 10_000));

    public static final int KARMA = 2;
    public static final int OBSIDIAN_SHARD = 19925;
    public static final int BLOODSTONE_SHARD = 20797;

    /**
     * Owner-selected Forge-project-only vendor facts (never part of {@link #recipes()}, so ordinary Profit/Discovery are
     * unchanged): Obsidian Shard from Tactician Deathstrider for 2,100 Karma; Bloodstone Shard from Miyani for 200 Spirit Shards.
     */
    private static final List<Recipe> FORGE_ONLY = List.of(
            Recipe.vendor(-(ID_BASE + OBSIDIAN_SHARD), OBSIDIAN_SHARD, 1, List.of(new CurrencyRequirement(KARMA, 2100)), 0),
            Recipe.vendor(-(ID_BASE + BLOODSTONE_SHARD), BLOODSTONE_SHARD, 1, List.of(new CurrencyRequirement(SPIRIT_SHARD, 200)), 0));

    private VendorAcquisitions() { }

    public static List<Recipe> forgeProjectRecipes() {
        List<Recipe> all = new java.util.ArrayList<>(RECIPES);
        all.addAll(FORGE_ONLY);
        return all;
    }

    /** Where/what a merchant step says (item id to short instruction); null when no verified merchant text exists. */
    public static String merchantStep(int itemId) {
        return switch (itemId) {
            case OBSIDIAN_SHARD -> "Tactician Deathstrider, Temple of Balthazar / Cathedral of Glorious Victory, Straits of Devastation (after the temple is secured): 2,100 Karma each. https://wiki.guildwars2.com/wiki/Tactician_Deathstrider";
            case BLOODSTONE_SHARD -> "Miyani (Mystic Forge Attendant) near the Mystic Forge, Trader's Forum, Lion's Arch: 200 Spirit Shards each. https://wiki.guildwars2.com/wiki/Bloodstone_Shard";
            case 20796, 20799, 20852 -> "Miyani (Mystic Forge Attendant) near the Mystic Forge, Trader's Forum, Lion's Arch: Spirit Shards. https://wiki.guildwars2.com/wiki/Miyani";
            default -> null;
        };
    }

    /** A short access hint for a purchase whose account requirement is known but not verifiable per account; else null. */
    public static String hintFor(int itemId) {
        return itemId == MYSTIC_RUNESTONE ? "Requires the Scholar of Secrets mastery to buy (your mastery is not checked)" : null;
    }

    public static List<Recipe> recipes() {
        return RECIPES;
    }

    /** Stable text of every vendor fact for calculation-input/cache identity. */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder("vendor-v2");
        for (Recipe r : forgeProjectRecipes()) {
            sb.append('|').append(r.outputItemId).append('x').append(r.outputCount).append('c').append(r.vendorCopper);
            r.currencies.forEach(c -> sb.append('s').append(c.currencyId()).append(':').append(c.count()));
        }
        return sb.toString();
    }
}
