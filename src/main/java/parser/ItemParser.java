package parser;

import com.fasterxml.jackson.databind.JsonNode;

public final class ItemParser {
    private ItemParser() {}

    public record ItemRow(
            int itemId,
            String name,
            String type,
            String rarity,
            Integer vendorValue,   // nullable ok
            String iconUrl         // nullable ok (optional, aber praktisch)
    ) {}

    public static ItemRow parse(JsonNode it) {
        if (it == null || it.isNull()) return null;

        int itemId = it.path("id").asInt(0);
        if (itemId <= 0) return null;

        String name   = it.hasNonNull("name")   ? it.get("name").asText()   : null;
        String type   = it.hasNonNull("type")   ? it.get("type").asText()   : null;
        String rarity = it.hasNonNull("rarity") ? it.get("rarity").asText() : null;

        Integer vendorValue = it.hasNonNull("vendor_value") ? it.get("vendor_value").asInt() : null;

        String iconUrl = it.hasNonNull("icon") ? it.get("icon").asText() : null;

        return new ItemRow(itemId, name, type, rarity, vendorValue, iconUrl);
    }
}