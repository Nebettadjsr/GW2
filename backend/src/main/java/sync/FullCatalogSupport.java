package sync;

import com.fasterxml.jackson.databind.JsonNode;

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

    /** Structurally valid requirement kinds this application knows but cannot plan (recipe is skipped, never free). */
    static final Set<String> KNOWN_UNSUPPORTED_KINDS = Set.of("GuildUpgrade");

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

    private static boolean intAbove(JsonNode n, String f, int min) {
        JsonNode v = n.get(f);
        return v != null && v.isInt() && v.asInt() >= min;
    }

    private static boolean optionalText(JsonNode n, String f) {
        return !n.hasNonNull(f) || n.get(f).isTextual();
    }

    private static boolean optionalTextArray(JsonNode n, String f) {
        if (!n.hasNonNull(f)) return true;
        if (!n.get(f).isArray()) return false;
        for (JsonNode e : n.get(f)) if (!e.isTextual()) return false;
        return true;
    }

    /**
     * Full-operation only: a stored {@code /v2/items} record needs a positive id, text name, type and
     * rarity and a non-negative integer vendor value; optional icon must be text and flags an array of
     * text. The tolerant ordinary {@code ItemParser} is untouched.
     */
    public static JsonNode requireItem(JsonNode n) {
        boolean ok = n != null && n.isObject() && intAbove(n, "id", 1)
                && n.hasNonNull("name") && n.get("name").isTextual()
                && n.hasNonNull("type") && n.get("type").isTextual()
                && n.hasNonNull("rarity") && n.get("rarity").isTextual()
                && intAbove(n, "vendor_value", 0)
                && optionalText(n, "icon")
                && (!n.hasNonNull("flags") || n.get("flags").isArray() && optionalTextArray(n, "flags"));
        if (!ok) throw new IllegalStateException("items detail batch contains a malformed record");
        return n;
    }

    /**
     * Strict typed ingredient boundary shared by the full refresh and the scheduled safe sync. Pinned schema
     * {type: Item|Currency, id, count}; legacy {item_id, count} stays valid. Identity is the (kind, id) pair, so an
     * Item and a Currency with equal numbers never collide and an unsupported kind is rejected, not dropped.
     */
    static boolean validIngredients(JsonNode ing) {
        return validIngredients(ing, false);
    }

    private static boolean validIngredients(JsonNode ing, boolean typedOnly) {
        if (!ing.isArray()) return false;
        Set<String> identities = new HashSet<>();
        for (JsonNode i : ing) {
            if (typedOnly && !(i.isObject() && i.hasNonNull("type"))) continue; // tolerated legacy shape
            String kind;
            String idField;
            if (i.isObject() && i.hasNonNull("type")) {
                kind = i.get("type").isTextual() ? i.get("type").asText() : "";
                idField = "id";
                if (!"Item".equals(kind) && !"Currency".equals(kind) && !KNOWN_UNSUPPORTED_KINDS.contains(kind)) return false;
            } else {
                kind = "Item";
                idField = "item_id";
            }
            if (!i.isObject() || !intAbove(i, idField, 1) || !intAbove(i, "count", 1)
                    || !identities.add(kind + ":" + i.get(idField).asInt())) return false;
        }
        return true;
    }

    /**
     * Safe-sync guard: throws when any typed (Item/Currency/...) ingredient of the recipe is malformed so the whole
     * recipe is quarantined and never published without a dropped requirement. Untyped legacy entries keep the
     * safe path's historical tolerance.
     */
    public static void requireIngredients(JsonNode recipe) {
        JsonNode ing = recipe.get("ingredients");
        if (ing != null && !ing.isNull() && !validIngredients(ing, true))
            throw new IllegalStateException("recipes detail batch contains a malformed record");
    }

    /**
     * Full-operation only: a recipe needs a positive id, text type, and either a positive item output
     * with a positive count or (guild upgrade recipes) a positive {@code output_upgrade_id};
     * non-negative rating and craft time; typed optional fields; and well-formed, distinct,
     * positive-quantity ingredients (a missing array is only legitimate for a guild upgrade recipe).
     * Malformed ingredients are rejected rather than skipped, so no incomplete recipe is published.
     */
    public static JsonNode requireRecipe(JsonNode n) {
        boolean ok = n != null && n.isObject() && intAbove(n, "id", 1)
                && n.hasNonNull("type") && n.get("type").isTextual()
                && intAbove(n, "min_rating", 0) && intAbove(n, "time_to_craft_ms", 0)
                && optionalText(n, "chat_link")
                && optionalTextArray(n, "disciplines") && optionalTextArray(n, "flags")
                && (!n.hasNonNull("guild_ingredients") || n.get("guild_ingredients").isArray());
        boolean itemOutput = ok && n.has("output_item_id");
        if (ok) {
            ok = itemOutput
                    ? intAbove(n, "output_item_id", 1) && intAbove(n, "output_item_count", 1)
                    : intAbove(n, "output_upgrade_id", 1);
        }
        if (ok) {
            JsonNode ing = n.get("ingredients");
            if (ing == null || ing.isNull()) {
                ok = !itemOutput;
            } else {
                ok = validIngredients(ing) && !(itemOutput && ing.isEmpty());
            }
        }
        if (!ok) throw new IllegalStateException("recipes detail batch contains a malformed record");
        return n;
    }
}
