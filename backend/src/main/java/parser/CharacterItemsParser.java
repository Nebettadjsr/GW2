package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterItemRow;

import java.util.ArrayList;
import java.util.List;

public final class CharacterItemsParser {
    private CharacterItemsParser() {}

    /** Parses the character-detail "bags" array (array of bags, each with an "inventory" array of slots). */
    public static List<CharacterItemRow> parseBags(JsonNode bagsArr) {
        List<CharacterItemRow> out = new ArrayList<>();
        if (bagsArr == null || bagsArr.isNull() || !bagsArr.isArray()) return out;

        for (int bagIndex = 0; bagIndex < bagsArr.size(); bagIndex++) {
            JsonNode bag = bagsArr.get(bagIndex);
            if (bag == null || bag.isNull()) continue;

            JsonNode inventory = bag.get("inventory");
            if (inventory == null || inventory.isNull() || !inventory.isArray()) continue;

            for (int slotIndex = 0; slotIndex < inventory.size(); slotIndex++) {
                ParsedItem item = parseItem(inventory.get(slotIndex));
                if (item == null) continue;

                out.add(new CharacterItemRow("BAG", bagIndex, slotIndex, null,
                        item.itemId(), item.count(), item.binding(), item.boundTo()));
            }
        }

        return out;
    }

    /** Parses the character-detail "equipment" array (flat array of equipped items, each carrying its own "slot"). */
    public static List<CharacterItemRow> parseEquipment(JsonNode equipmentArr) {
        List<CharacterItemRow> out = new ArrayList<>();
        if (equipmentArr == null || equipmentArr.isNull() || !equipmentArr.isArray()) return out;

        for (JsonNode entry : equipmentArr) {
            ParsedItem item = parseItem(entry);
            if (item == null) continue;

            String equipmentSlot = entry.path("slot").asText(null);
            if (equipmentSlot == null || equipmentSlot.isBlank()) continue;

            out.add(new CharacterItemRow("EQUIPMENT", null, null, equipmentSlot,
                    item.itemId(), item.count(), item.binding(), item.boundTo()));
        }

        return out;
    }

    private record ParsedItem(int itemId, Integer count, String binding, String boundTo) {}

    // Mirrors BankParser.parseSlot's field extraction for item_id/count/binding/bound_to.
    private static ParsedItem parseItem(JsonNode entry) {
        if (entry == null || entry.isNull() || !entry.hasNonNull("id")) return null;

        int itemId = entry.get("id").asInt();
        Integer count = entry.hasNonNull("count") ? entry.get("count").asInt() : null;
        String binding = entry.hasNonNull("binding") ? entry.get("binding").asText() : null;
        String boundTo = entry.hasNonNull("bound_to") ? entry.get("bound_to").asText() : null;

        return new ParsedItem(itemId, count, binding, boundTo);
    }
}
