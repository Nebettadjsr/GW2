package craft;

import java.util.List;

/**
 * A validated Mystic Forge recipe snapshot as a plain domain value (no JSON/classpath/filesystem concerns;
 * {@code parser.MysticForgeCatalogLoader} reads the bundled snapshot generated offline from the ArenaNet-hosted
 * GW2 Wiki; the backend never calls the Wiki at runtime). Content: GW2 Wiki contributors, GFDL 1.3; game assets remain ArenaNet's. The snapshot's recorded
 * coverage and exclusions are exposed so they are never mistaken for "all Forge recipes".
 */
public final class MysticForgeCatalog {

    private final List<Recipe> recipes;
    private final int excludedCount;
    private final String source;

    public MysticForgeCatalog(List<Recipe> recipes, int excludedCount, String source) {
        this.recipes = List.copyOf(recipes);
        this.excludedCount = excludedCount;
        this.source = source;
    }

    public List<Recipe> recipes() { return recipes; }
    public int excludedCount() { return excludedCount; }
    public String source() { return source; }

    /** Stable content fingerprint for calculation/detail cache identity. */
    public String fingerprint() {
        long h = 1125899906842597L;
        for (Recipe r : recipes) {
            h = 31 * h + r.recipeId;
            h = 31 * h + r.outputItemId;
            h = 31 * h + r.outputCount;
            if (r.expectedYield != null) h = 31 * h + r.expectedYield.mean().stripTrailingZeros().hashCode() * 31L + r.expectedYield.max();
            for (Ingredient i : r.ingredients) h = 31 * h + (i.itemId * 31L + i.count);
        }
        return recipes.size() + ":" + Long.toHexString(h);
    }
}
