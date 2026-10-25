package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterCraftingRow;

import java.util.ArrayList;
import java.util.List;

public final class CharacterCraftingParser {
    private CharacterCraftingParser() {}

    public static List<CharacterCraftingRow> parse(JsonNode craftingArr) {
        List<CharacterCraftingRow> out = new ArrayList<>();
        if (craftingArr == null || craftingArr.isNull() || !craftingArr.isArray()) return out;

        for (JsonNode row : craftingArr) {
            if (row == null || row.isNull()) continue;

            String disc = row.path("discipline").asText(null);
            if (disc == null || disc.isBlank()) continue;

            int rating = row.path("rating").asInt(0);
            boolean active = row.path("active").asBoolean(false);

            out.add(new CharacterCraftingRow(disc, rating, active));
        }

        return out;
    }
}