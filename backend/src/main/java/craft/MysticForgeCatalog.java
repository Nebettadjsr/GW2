package craft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A validated Mystic Forge recipe snapshot as a plain domain value (no JSON/classpath/filesystem concerns;
 * {@code parser.MysticForgeCatalogLoader} reads the bundled snapshot generated offline from the ArenaNet-hosted
 * GW2 Wiki by {@code backend/tools/mystic-forge/generate_catalog.py}; the backend never calls the Wiki at runtime).
 * Content: GW2 Wiki contributors, GFDL 1.3; game assets remain ArenaNet's. Coverage, exclusions and any load
 * problem are exposed so the snapshot is never mistaken for "all Forge recipes" and a broken resource is observable.
 */
public final class MysticForgeCatalog {

    /** Where one recipe came from: its Wiki page/subobject and the page revision the facts were captured at. */
    public record Provenance(String page, long revision) { }

    /** Source row accounting: strict totals plus the reason every excluded row was left out. */
    public record Coverage(int sourceRows, int includedRecipes, int fixedOutput, int expectedYield,
                           Map<String, Integer> excludedByReason) {
        public Coverage {
            excludedByReason = Map.copyOf(excludedByReason);
        }
    }

    public static final String STATUS_OK = "ok";

    private final List<Recipe> recipes;
    private final Map<Integer, Provenance> provenance;
    private final Coverage coverage;
    private final String source;
    private final String license;
    private final String status;
    private final String fingerprint;

    public MysticForgeCatalog(List<Recipe> recipes, int excludedCount, String source) {
        this(recipes, Map.of(), new Coverage(recipes.size() + excludedCount, recipes.size(),
                (int) recipes.stream().filter(r -> !r.hasExpectedYield()).count(),
                (int) recipes.stream().filter(Recipe::hasExpectedYield).count(),
                excludedCount == 0 ? Map.of() : Map.of("unspecified", excludedCount)), source, "", STATUS_OK);
    }

    public MysticForgeCatalog(List<Recipe> recipes, Map<Integer, Provenance> provenance, Coverage coverage,
                              String source, String license, String status) {
        this.recipes = List.copyOf(recipes);
        this.provenance = Map.copyOf(provenance);
        this.coverage = coverage;
        this.source = source;
        this.license = license;
        this.status = status;
        this.fingerprint = computeFingerprint();
    }

    /** An empty catalog standing for a missing/unreadable/invalid resource; {@code problem} says why. */
    public static MysticForgeCatalog unavailable(String problem) {
        return new MysticForgeCatalog(List.of(), Map.of(), new Coverage(0, 0, 0, 0, Map.of()), "", "", problem);
    }

    public List<Recipe> recipes() { return recipes; }
    public int excludedCount() { return coverage.sourceRows() - coverage.includedRecipes(); }
    public String source() { return source; }
    public String license() { return license; }
    public Coverage coverage() { return coverage; }
    public Provenance provenance(int recipeId) { return provenance.get(recipeId); }
    /** {@link #STATUS_OK} or the reason the resource supplied no recipes. */
    public String status() { return status; }
    public boolean isUsable() { return STATUS_OK.equals(status); }

    /** Stable SHA-256 over every meaningful fact (recipes, yields, provenance, status) for cache identity. */
    public String fingerprint() { return fingerprint; }

    private String computeFingerprint() {
        StringBuilder sb = new StringBuilder("forge-catalog-v2|").append(status).append('|').append(source);
        Map<Integer, Recipe> sorted = new TreeMap<>();
        for (Recipe r : recipes) sorted.put(r.recipeId, r);
        for (Recipe r : sorted.values()) {
            sb.append("\nR").append(r.recipeId).append(':').append(r.outputItemId).append('x').append(r.outputCount);
            if (r.expectedYield != null) {
                sb.append(" Y").append(r.expectedYield.mean().stripTrailingZeros().toPlainString())
                        .append('/').append(r.expectedYield.min()).append('/').append(r.expectedYield.max());
            }
            for (Ingredient i : r.ingredients) sb.append(" I").append(i.itemId).append('x').append(i.count);
            for (CurrencyRequirement c : r.currencies) sb.append(" C").append(c);
            Provenance p = provenance.get(r.recipeId);
            if (p != null) sb.append(" P").append(p.page()).append('@').append(p.revision());
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return recipes.size() + ":" + HexFormat.of().formatHex(d, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
