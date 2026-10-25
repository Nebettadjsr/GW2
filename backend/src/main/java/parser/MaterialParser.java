package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.MaterialStack;

public final class MaterialParser {
    private MaterialParser() {}

    public static MaterialStack parse(JsonNode entry) {
        if (entry == null || entry.isNull()) return null;

        int itemId   = entry.path("id").asInt(0);
        int category = entry.path("category").asInt(0);
        int count    = entry.path("count").asInt(0);

        if (itemId <= 0) return null; // safety

        String binding = entry.hasNonNull("binding") ? entry.get("binding").asText() : null;

        return new MaterialStack(itemId, category, count, binding);
    }
}