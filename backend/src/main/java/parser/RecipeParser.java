package parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class RecipeParser {

    private RecipeParser() {}

    public record RecipeRow(
            int recipeId,
            String type,
            int outputItem,
            int outputCount,
            int minRating,
            int craftTime,
            String[] disciplines,
            String[] flags,
            String chatLink,
            String guildIngredientsJson
    ) {}

    public record IngredientRow(
            int recipeId,
            int itemId,
            int count
    ) {}

    /**
     * A typed non-item recipe requirement, e.g. {@code Currency} 61 (Research Note). It is kept apart from
     * {@link IngredientRow} so item-only economics are unchanged and a currency id is never mistaken for an item id.
     */
    public record RequirementRow(int recipeId, String kind, int refId, int count) {}

    public static RecipeRow parseRecipe(JsonNode r) {

        if (r == null || r.isNull()) return null;

        int recipeId = r.path("id").asInt(0);
        if (recipeId <= 0) return null;

        String type = r.path("type").asText(null);
        int outputItem = r.path("output_item_id").asInt(0);
        int outputCount = r.path("output_item_count").asInt(0);
        int minRating = r.path("min_rating").asInt(0);
        int craftTime = r.path("time_to_craft_ms").asInt(0);

        String chatLink = r.hasNonNull("chat_link") ? r.get("chat_link").asText() : null;
        String guildIngredientsJson =
                r.hasNonNull("guild_ingredients")
                ? r.get("guild_ingredients").toString()
                : null;

        String[] discs = parseStringArray(r.get("disciplines"));
        String[] flags = parseStringArray(r.get("flags"));

        return new RecipeRow(
                recipeId,
                type,
                outputItem,
                outputCount,
                minRating,
                craftTime,
                discs,
                flags,
                chatLink,
                guildIngredientsJson
        );
    }

    public static List<IngredientRow> parseIngredients(JsonNode r) {

        List<IngredientRow> rows = new ArrayList<>();

        if (r == null) return rows;

        int recipeId = r.path("id").asInt(0);
        if (recipeId <= 0) return rows;

        JsonNode ingredients = r.get("ingredients");

        if (ingredients == null || !ingredients.isArray()) return rows;

        for (JsonNode ing : ingredients) {

            // Same identity rule as the sync validator: typed entries use only "id", legacy ones only "item_id".
            boolean typed = ing.has("type");
            if (typed && !"Item".equals(ing.get("type").asText())) continue;
            int itemId = ing.path(typed ? "id" : "item_id").asInt(0);
            int count = ing.path("count").asInt(0);

            if (itemId <= 0 || count <= 0) continue;

            rows.add(new IngredientRow(recipeId, itemId, count));
        }

        return rows;
    }

    /** Typed non-item requirements (currencies and any other unrecognised kind) of one recipe, in payload order. */
    public static List<RequirementRow> parseNonItemRequirements(JsonNode r) {
        List<RequirementRow> rows = new ArrayList<>();
        if (r == null) return rows;
        int recipeId = r.path("id").asInt(0);
        JsonNode ingredients = r.get("ingredients");
        if (recipeId <= 0 || ingredients == null || !ingredients.isArray()) return rows;
        for (JsonNode ing : ingredients) {
            if (!ing.hasNonNull("type") || "Item".equals(ing.get("type").asText())) continue;
            int refId = ing.path("id").asInt(0);
            int count = ing.path("count").asInt(0);
            if (refId <= 0 || count <= 0) continue;
            rows.add(new RequirementRow(recipeId, ing.get("type").asText(), refId, count));
        }
        return rows;
    }

    /** True when an ingredient has a type this application cannot honour (neither Item nor Currency). */
    public static boolean hasUnsupportedRequirement(JsonNode r) {
        JsonNode ingredients = r == null ? null : r.get("ingredients");
        if (ingredients == null || !ingredients.isArray()) return false;
        for (JsonNode ing : ingredients) {
            if (!ing.hasNonNull("type")) continue;
            String kind = ing.get("type").asText();
            if (!"Item".equals(kind) && !"Currency".equals(kind)) return true;
        }
        return false;
    }

    private static String[] parseStringArray(JsonNode node) {

        if (node == null || !node.isArray()) return new String[0];

        String[] arr = new String[node.size()];

        for (int i = 0; i < arr.length; i++) {
            arr[i] = node.get(i).asText();
        }

        return arr;
    }
}