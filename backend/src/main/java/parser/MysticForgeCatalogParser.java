package parser;

import com.fasterxml.jackson.databind.JsonNode;
import craft.Ingredient;
import craft.Recipe;
import craft.RecipeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates GW2 Wiki Semantic MediaWiki {@code action=ask} results for
 * {@code [[Has recipe source::Mystic forge]]} into strict Mystic Forge recipes. A row that is malformed,
 * lacks a wiki forge id/output/ingredient, or whose output is not <em>verified fixed</em> is excluded with a
 * reason - never turned into a free or guaranteed recipe. SMW stores only the lower output quantity
 * (Template:Recipe's upper quantity is rendered but not stored), so a row is fixed only when its forge id is in
 * the explicitly verified set. Pure: no network, no persistence.
 */
public final class MysticForgeCatalogParser {

    public record Exclusion(String page, String reason) { }

    public record Result(List<Recipe> recipes, List<Exclusion> exclusions) { }

    private MysticForgeCatalogParser() { }

    /** Recipe id of a Mystic Forge recipe: negated wiki forge id, so it never collides with an ArenaNet id. */
    public static int recipeIdFor(int forgeId) {
        return -forgeId;
    }

    public static Result parse(JsonNode root, Set<Integer> verifiedFixedForgeIds) {
        List<Recipe> recipes = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();
        Map<Integer, String> seen = new LinkedHashMap<>();
        JsonNode results = root == null ? null : root.path("query").path("results");
        if (results == null || !results.isObject()) {
            exclusions.add(new Exclusion("*", "no query.results object"));
            return new Result(recipes, exclusions);
        }
        results.fields().forEachRemaining(e -> {
            String page = e.getKey();
            String problem = null;
            Recipe recipe = null;
            try {
                JsonNode p = e.getValue().path("printouts");
                Integer forgeId = single(p.path("Has mystic forge id"));
                Integer output = single(p.path("Has output game id"));
                Integer qty = single(p.path("Has output quantity"));
                if (forgeId == null || forgeId <= 0) problem = "missing wiki forge id";
                else if (output == null || output <= 0) problem = "missing output item id";
                else if (qty == null || qty <= 0) problem = "missing output quantity";
                else if (seen.containsKey(forgeId)) problem = "duplicate forge id " + forgeId + " (also " + seen.get(forgeId) + ")";
                else {
                    List<Ingredient> ingredients = ingredients(p.path("Has ingredient with id"));
                    if (ingredients == null) problem = "missing or malformed ingredient ids/counts";
                    else if (!verifiedFixedForgeIds.contains(forgeId)) problem = "output not verified fixed (random or unknown yield)";
                    else {
                        seen.put(forgeId, page);
                        recipe = new Recipe(recipeIdFor(forgeId), output, qty, 0, "", ingredients,
                                List.of(), RecipeSource.MYSTIC_FORGE);
                    }
                }
            } catch (RuntimeException ex) {
                problem = "malformed row";
            }
            if (recipe != null) recipes.add(recipe); else exclusions.add(new Exclusion(page, problem));
        });
        return new Result(recipes, exclusions);
    }

    /** Aggregates repeated slots by item id; null when any slot lacks a positive id or count. */
    private static List<Ingredient> ingredients(JsonNode records) {
        if (!records.isArray() || records.isEmpty()) return null;
        Map<Integer, Integer> byItem = new LinkedHashMap<>();
        for (JsonNode r : records) {
            Integer id = single(r.path("Has ingredient id").path("item"));
            Integer count = single(r.path("Has ingredient quantity").path("item"));
            if (id == null || id <= 0 || count == null || count <= 0) return null;
            byItem.merge(id, count, Math::addExact);
        }
        List<Ingredient> out = new ArrayList<>();
        byItem.forEach((id, count) -> out.add(new Ingredient(id, count)));
        return out;
    }

    /** The only integer of a one-element array (or a bare integer); otherwise null. */
    private static Integer single(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isArray()) {
            if (n.size() != 1) return null;
            n = n.get(0);
        }
        return n.isInt() && n.canConvertToInt() ? n.intValue() : null;
    }
}
