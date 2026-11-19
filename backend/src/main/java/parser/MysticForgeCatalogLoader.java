package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.ExpectedYield;
import craft.Ingredient;
import craft.MysticForgeCatalog;
import craft.Recipe;
import craft.RecipeSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads the bundled Mystic Forge snapshot (schema 1, written by {@code backend/tools/mystic-forge/generate_catalog.py})
 * into the domain {@link MysticForgeCatalog}. Scalars are strictly typed (no coercion of {@code 1.5} or {@code "1"}),
 * a recipe that fails any check is rejected with a counted reason, and every copy of a duplicated identity is
 * rejected (no arbitrary "first" version stays usable). A missing/unreadable/wrong-schema resource yields an empty
 * catalog whose {@code status()} names the problem and which is logged - it never supplies a recipe.
 */
public final class MysticForgeCatalogLoader {

    public static final String RESOURCE = "/mystic-forge/catalog.json";
    private static final System.Logger LOG = System.getLogger(MysticForgeCatalogLoader.class.getName());
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,6}(\\.\\d{1,6})?");

    private static final MysticForgeCatalog BUNDLED = load(RESOURCE);

    private MysticForgeCatalogLoader() { }

    public static MysticForgeCatalog bundled() { return BUNDLED; }

    static MysticForgeCatalog load(String resource) {
        try (InputStream in = MysticForgeCatalogLoader.class.getResourceAsStream(resource)) {
            if (in == null) return failed("missing resource " + resource, null);
            return fromJson(new ObjectMapper().readTree(in));
        } catch (IOException | RuntimeException e) {
            return failed("unreadable resource " + resource + ": " + e.getMessage(), e);
        }
    }

    private static MysticForgeCatalog failed(String problem, Throwable cause) {
        LOG.log(System.Logger.Level.ERROR, "Mystic Forge catalog unavailable: " + problem, cause);
        return MysticForgeCatalog.unavailable(problem);
    }

    public static MysticForgeCatalog fromJson(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != 1 || !root.path("recipes").isArray()) {
            return failed("not a schema 1 catalog", null);
        }
        Map<Integer, Integer> idCounts = new HashMap<>();
        for (JsonNode r : root.path("recipes")) {
            JsonNode id = r.path("forgeId");
            if (id.isInt()) idCounts.merge(id.intValue(), 1, Integer::sum);
        }
        List<Recipe> out = new ArrayList<>();
        Map<Integer, MysticForgeCatalog.Provenance> provenance = new LinkedHashMap<>();
        Map<String, Integer> rejected = new TreeMap<>();
        for (JsonNode r : root.path("recipes")) {
            String problem = problem(r, idCounts);
            if (problem != null) {
                rejected.merge(problem, 1, Integer::sum);
                continue;
            }
            Recipe recipe = toRecipe(r);
            out.add(recipe);
            provenance.put(recipe.recipeId, new MysticForgeCatalog.Provenance(
                    r.path("page").textValue(), r.path("revision").longValue()));
        }
        Map<String, Integer> excluded = new TreeMap<>();
        JsonNode cov = root.path("coverage");
        JsonNode by = cov.path("excludedByReason");
        if (by.isObject()) by.fields().forEachRemaining(e -> { if (e.getValue().isInt()) excluded.merge(e.getKey(), e.getValue().intValue(), Integer::sum); });
        rejected.forEach((k, v) -> excluded.merge("rejected at load: " + k, v, Integer::sum));
        if (!rejected.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Mystic Forge catalog rejected recipes at load: " + rejected);
        }
        int fixed = (int) out.stream().filter(x -> !x.hasExpectedYield()).count();
        int sourceRows = cov.path("sourceRows").isInt() ? cov.path("sourceRows").intValue() : out.size();
        var coverage = new MysticForgeCatalog.Coverage(sourceRows, out.size(), fixed, out.size() - fixed, excluded);
        JsonNode src = root.path("source");
        return new MysticForgeCatalog(out, provenance, coverage, src.path("name").asText("") + " " + src.path("url").asText(""),
                src.path("license").asText(""), MysticForgeCatalog.STATUS_OK);
    }

    private static boolean posInt(JsonNode n) { return n.isInt() && n.intValue() > 0; }

    /** Reason a recipe node is not valid, or null. */
    private static String problem(JsonNode r, Map<Integer, Integer> idCounts) {
        if (!r.isObject()) return "recipe is not an object";
        if (!posInt(r.path("forgeId"))) return "forgeId is not a positive integer";
        if (idCounts.get(r.path("forgeId").intValue()) > 1) return "duplicate forgeId";
        if (!posInt(r.path("outputItemId"))) return "outputItemId is not a positive integer";
        if (!posInt(r.path("outputCount"))) return "outputCount is not a positive integer";
        if (!r.path("page").isTextual() || r.path("page").textValue().isBlank()) return "missing source page";
        if (!r.path("revision").isIntegralNumber() || r.path("revision").longValue() <= 0) return "missing source revision";
        JsonNode ing = r.path("ingredients");
        if (!ing.isArray() || ing.isEmpty()) return "no ingredients";
        Set<Integer> items = new HashSet<>();
        for (JsonNode i : ing) {
            if (!i.isArray() || i.size() != 2 || !posInt(i.get(0)) || !posInt(i.get(1))) return "ingredient is not [positive id, positive count]";
            if (!items.add(i.get(0).intValue())) return "repeated ingredient item";
        }
        JsonNode y = r.get("yield");
        if (y != null) {
            if (!y.isObject() || !y.path("mean").isTextual() || !DECIMAL.matcher(y.path("mean").textValue()).matches()
                    || !posInt(y.path("min")) || !posInt(y.path("max")) || !y.path("category").isTextual()) {
                return "malformed expected yield";
            }
            if (y.path("min").intValue() != r.path("outputCount").intValue()) return "yield min differs from outputCount";
            try {
                ExpectedYield.of(y.path("mean").textValue(), y.path("min").intValue(), y.path("max").intValue());
            } catch (IllegalArgumentException e) {
                return "yield mean outside min..max";
            }
        }
        return null;
    }

    private static Recipe toRecipe(JsonNode r) {
        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonNode i : r.path("ingredients")) ingredients.add(new Ingredient(i.get(0).intValue(), i.get(1).intValue()));
        int id = MysticForgeCatalogParser.recipeIdFor(r.path("forgeId").intValue());
        JsonNode y = r.get("yield");
        if (y != null) {
            return Recipe.forgeWithExpectedYield(id, r.path("outputItemId").intValue(),
                    new ExpectedYield(new BigDecimal(y.path("mean").textValue()), y.path("min").intValue(), y.path("max").intValue()),
                    ingredients);
        }
        return new Recipe(id, r.path("outputItemId").intValue(), r.path("outputCount").intValue(), 0, "", ingredients,
                List.of(), RecipeSource.MYSTIC_FORGE);
    }
}
