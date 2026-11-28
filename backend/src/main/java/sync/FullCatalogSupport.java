package sync;

import com.fasterxml.jackson.databind.JsonNode;
import parser.PayloadValidators;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Strict validation for the owner full-catalog operations. Failures name the endpoint kind and the
 * defect only, never the payload, and none of them may be treated as a successful run.
 */
public final class FullCatalogSupport {
    private FullCatalogSupport() {}

    /** One finite upstream ID snapshot: a non-empty array of distinct positive integers. */
    static List<Integer> parseIdSnapshot(JsonNode root, String what) {
        if (root == null || !root.isArray()) throw new IllegalStateException(what + " id snapshot is not an array");
        Set<Integer> ids = new LinkedHashSet<>();
        for (JsonNode n : root) {
            if (n == null || !n.isInt() || n.asInt() <= 0) {
                throw new IllegalStateException(what + " id snapshot contains a malformed id");
            }
            if (!ids.add(n.asInt())) throw new IllegalStateException(what + " id snapshot contains a duplicate id");
        }
        if (ids.isEmpty()) throw new IllegalStateException(what + " id snapshot is empty");
        return new ArrayList<>(ids);
    }

    /**
     * Parses a detail batch that must contain exactly the requested IDs: an array, every record
     * well-formed, no duplicate, no unexpected ID and no missing requested ID.
     */
    static <T> List<T> parseExact(JsonNode root, List<Integer> requested, Function<JsonNode, T> parse,
                                  ToIntFunction<T> idOf, String what) {
        if (root == null || !root.isArray()) throw new IllegalStateException(what + " detail batch is not an array");
        Set<Integer> wanted = new HashSet<>(requested);
        Set<Integer> seen = new HashSet<>();
        List<T> out = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            T row = parse.apply(node);
            if (row == null) throw new IllegalStateException(what + " detail batch contains a malformed record");
            int id = idOf.applyAsInt(row);
            if (!wanted.contains(id)) throw new IllegalStateException(what + " detail batch contains an unexpected id");
            if (!seen.add(id)) throw new IllegalStateException(what + " detail batch contains a duplicate id");
            out.add(row);
        }
        if (seen.size() != wanted.size()) throw new IllegalStateException(what + " detail batch is missing requested ids");
        return out;
    }

    /**
     * Full-operation only: a stored {@code /v2/items} record needs a positive id, text name, type and
     * rarity and a non-negative integer vendor value; optional icon must be text and flags an array of
     * text. The tolerant ordinary {@code ItemParser} is untouched.
     */
    public static JsonNode requireItem(JsonNode n) {
        boolean ok = n != null && n.isObject() && PayloadValidators.intAbove(n, "id", 1)
                && n.hasNonNull("name") && n.get("name").isTextual()
                && n.hasNonNull("type") && n.get("type").isTextual()
                && n.hasNonNull("rarity") && n.get("rarity").isTextual()
                && PayloadValidators.intAbove(n, "vendor_value", 0)
                && PayloadValidators.optionalText(n, "icon")
                && (!n.hasNonNull("flags") || n.get("flags").isArray() && PayloadValidators.optionalTextArray(n, "flags"));
        if (!ok) throw new IllegalStateException("items detail batch contains a malformed record");
        return n;
    }

    /**
     * Safe-sync guard: malformed typed or legacy ingredients quarantine the whole recipe rather than
     * dropping a requirement. Valid legacy item_id/count records remain supported.
     */
    public static void requireIngredients(JsonNode recipe) {
        JsonNode ing = recipe.get("ingredients");
        // Missing, null and non-array collections are malformed. As in full refresh, an empty collection is
        // permitted only for a non-item output; accepting it for an item would publish a requirement-free recipe.
        if (ing == null || !PayloadValidators.validIngredients(ing) || (recipe.has("output_item_id") && ing.isEmpty()))
            throw new IllegalStateException("recipes detail batch contains a malformed record");
    }
}
