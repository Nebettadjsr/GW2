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

            int itemId = ing.path("item_id").asInt(0);
            int count = ing.path("count").asInt(0);

            if (itemId <= 0 || count <= 0) continue;

            rows.add(new IngredientRow(recipeId, itemId, count));
        }

        return rows;
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