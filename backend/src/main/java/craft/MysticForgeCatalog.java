package craft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundled, validated Mystic Forge recipe snapshot ({@code /mystic-forge/catalog.json}), generated offline from
 * the ArenaNet-hosted GW2 Wiki by {@code parser.MysticForgeCatalogParser}; the backend never calls the Wiki at
 * runtime. Content: GW2 Wiki contributors, GFDL 1.3; game assets remain ArenaNet's. The snapshot's recorded
 * coverage and exclusions are exposed so they are never mistaken for "all Forge recipes".
 */
public final class MysticForgeCatalog {

    public static final String RESOURCE = "/mystic-forge/catalog.json";

    private static final MysticForgeCatalog BUNDLED = load(RESOURCE);

    private final List<Recipe> recipes;
    private final int excludedCount;
    private final String source;

    private MysticForgeCatalog(List<Recipe> recipes, int excludedCount, String source) {
        this.recipes = List.copyOf(recipes);
        this.excludedCount = excludedCount;
        this.source = source;
    }

    public static MysticForgeCatalog bundled() { return BUNDLED; }

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
            for (Ingredient i : r.ingredients) h = 31 * h + (i.itemId * 31L + i.count);
        }
        return recipes.size() + ":" + Long.toHexString(h);
    }

    static MysticForgeCatalog load(String resource) {
        try (InputStream in = MysticForgeCatalog.class.getResourceAsStream(resource)) {
            if (in == null) return new MysticForgeCatalog(List.of(), 0, "missing");
            return fromJson(new ObjectMapper().readTree(in));
        } catch (IOException | RuntimeException e) {
            // An unreadable snapshot supplies no Forge recipes; it never invents any.
            return new MysticForgeCatalog(List.of(), 0, "unreadable");
        }
    }

    /** Normalized form: {source, excludedCount, recipes:[{forgeId, outputItemId, outputCount, ingredients:[[id,count]]}]}. */
    public static MysticForgeCatalog fromJson(JsonNode root) {
        List<Recipe> out = new ArrayList<>();
        for (JsonNode r : root.path("recipes")) {
            int forgeId = r.path("forgeId").asInt(0), output = r.path("outputItemId").asInt(0),
                    qty = r.path("outputCount").asInt(0);
            List<Ingredient> ingredients = new ArrayList<>();
            for (JsonNode i : r.path("ingredients")) {
                ingredients.add(new Ingredient(i.path(0).asInt(0), i.path(1).asInt(0)));
            }
            boolean valid = forgeId > 0 && output > 0 && qty > 0 && !ingredients.isEmpty()
                    && ingredients.stream().allMatch(i -> i.itemId > 0 && i.count > 0);
            if (valid) {
                out.add(new Recipe(-forgeId, output, qty, 0, "", ingredients, List.of(), RecipeSource.MYSTIC_FORGE));
            }
        }
        return new MysticForgeCatalog(out, root.path("excludedCount").asInt(0), root.path("source").asText(""));
    }
}
