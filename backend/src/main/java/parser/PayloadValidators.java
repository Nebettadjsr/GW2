package parser;

import com.fasterxml.jackson.databind.JsonNode;
import util.TpPrice;

import java.util.HashSet;
import java.util.Set;

/**
 * The strict payload validators shared by the production sync and the API compatibility check, so the
 * check applies exactly what the sync enforces. Failures carry no payload content.
 */
public final class PayloadValidators {
    private PayloadValidators() {}

    /** Structurally valid requirement kinds this application knows but cannot plan (recipe is skipped, never free). */
    public static final Set<String> KNOWN_UNSUPPORTED_KINDS = Set.of("GuildUpgrade");

    public static boolean intAbove(JsonNode n, String f, int min) {
        JsonNode v = n.get(f);
        return v != null && v.isInt() && v.asInt() >= min;
    }

    public static boolean optionalText(JsonNode n, String f) {
        return !n.hasNonNull(f) || n.get(f).isTextual();
    }

    public static boolean optionalTextArray(JsonNode n, String f) {
        if (!n.hasNonNull(f)) return true;
        if (!n.get(f).isArray()) return false;
        for (JsonNode e : n.get(f)) if (!e.isTextual()) return false;
        return true;
    }

    /**
     * Strict ingredient boundary shared by the full refresh and the scheduled safe sync. Pinned schema
     * {type: Item|Currency, id, count}; legacy {item_id, count} stays valid. Identity is the (kind, id) pair, so an
     * Item and a Currency with equal numbers never collide and an unsupported kind is rejected, not dropped.
     */
    public static boolean validIngredients(JsonNode ing) {
        if (!ing.isArray()) return false;
        Set<String> identities = new HashSet<>();
        for (JsonNode i : ing) {
            String kind;
            String idField;
            if (i.isObject() && i.has("type")) {
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
     * Full-refresh validation, also used to classify safe-sync unsupported records: a recipe needs
     * a positive id, text type, and either a positive item output
     * with a positive count or (guild upgrade recipes) a positive {@code output_upgrade_id};
     * non-negative rating and craft time; typed optional fields; and well-formed, distinct,
     * positive-quantity ingredients (a missing array is only legitimate for a guild upgrade recipe).
     * Malformed ingredients are rejected rather than skipped, so no incomplete recipe is published.
     * An item recipe with a missing, null or empty ingredient list is rejected here as well; the one
     * record that is accepted for skipping instead is {@link #isIngredientlessItemRecipe(JsonNode)}, which
     * callers must test first and then skip (never publish) so the output cannot look free.
     */
    public static JsonNode requireRecipe(JsonNode n) {
        if (!validRecipe(n, false)) throw new IllegalStateException("recipes detail batch contains a malformed record");
        return n;
    }

    /**
     * The skip rule for a known unusable record: an item recipe that is valid in every other respect but has
     * a missing, null or empty ingredient list (an upstream placeholder such as recipes 13373 and 13390).
     * It is skipped and recorded, never published and never fatal. Any other malformed record is not matched.
     */
    public static boolean isIngredientlessItemRecipe(JsonNode n) {
        if (n == null || !n.isObject() || !n.has("output_item_id")) return false;
        JsonNode ing = n.get("ingredients");
        boolean noIngredients = ing == null || ing.isNull() || ing.isArray() && ing.isEmpty();
        return noIngredients && validRecipe(n, true);
    }

    private static boolean validRecipe(JsonNode n, boolean allowIngredientlessItem) {
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
                ok = !itemOutput || allowIngredientlessItem;
            } else {
                ok = validIngredients(ing) && (allowIngredientlessItem || !(itemOutput && ing.isEmpty()));
            }
        }
        return ok;
    }

    /** A quote needs a positive id and either both null sides or both well-formed non-negative sides. */
    public static TpPrice strictQuote(JsonNode p) {
        if (p == null || !p.isObject() || !p.has("id") || !p.get("id").isInt() || p.get("id").asInt() <= 0) {
            throw new IllegalStateException("tp quote is malformed");
        }
        // Both side fields must be present: an explicit JSON null is the supported "no market data"
        // shape, but an absent field (e.g. a bare {"id":1}) is a malformed quote.
        if (!p.has("buys") || !p.has("sells")) throw new IllegalStateException("tp quote is malformed");
        JsonNode buys = p.get("buys");
        JsonNode sells = p.get("sells");
        boolean noBuys = buys.isNull();
        boolean noSells = sells.isNull();
        if (noBuys && noSells) return TpPrice.noData(p.get("id").asInt());
        if (!validSide(buys) || !validSide(sells)) throw new IllegalStateException("tp quote is malformed");
        return new TpPrice(p.get("id").asInt(), buys.get("quantity").asLong(), buys.get("unit_price").asInt(),
                sells.get("quantity").asLong(), sells.get("unit_price").asInt());
    }

    private static boolean validSide(JsonNode side) {
        return side != null && side.isObject()
                && side.path("quantity").canConvertToLong() && side.path("quantity").isIntegralNumber()
                && side.get("quantity").asLong() >= 0
                && side.path("unit_price").isInt() && side.get("unit_price").asInt() >= 0;
    }
}
