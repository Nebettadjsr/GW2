package parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict parser for one character's {@code /v2/characters/:id/equipmenttabs?tabs=all} response
 * (STORY-CHAR-005). Anything that cannot be stored without guessing is a validation failure; absent
 * optional fields stay absent (no zero IDs, inferred rarity, fabricated stats or synthetic upgrades).
 */
public final class CharacterEquipmentTabsParser {
    private CharacterEquipmentTabsParser() {}

    /** @param stat selected stat id, null when the entry carries none; attributes null likewise */
    public record Entry(String slot, int itemId, String location, List<Integer> upgrades,
                        List<Integer> infusions, Integer statId, Map<String, Integer> statAttributes,
                        String binding, String boundTo) {}

    public record Tab(int number, String name, boolean active, List<Entry> equipment) {}

    /** @return tabs in in-game (ascending tab number) order, possibly empty */
    public static List<Tab> parse(JsonNode root) {
        if (root == null || !root.isArray()) throw invalid("response is not an array");
        List<Tab> tabs = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        int active = 0;
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) throw invalid("tab is not an object");
            JsonNode number = node.get("tab");
            if (!positiveInt(number)) throw invalid("tab number");
            if (!numbers.add(number.intValue())) throw invalid("duplicate tab number");
            JsonNode isActive = node.get("is_active");
            if (isActive == null || !isActive.isBoolean()) throw invalid("is_active");
            if (isActive.booleanValue()) active++;
            String name = optionalText(node, "name", "tab name");
            JsonNode equipment = node.get("equipment");
            if (equipment == null || !equipment.isArray()) throw invalid("equipment");
            List<Entry> entries = new ArrayList<>();
            Set<String> slots = new HashSet<>();
            for (JsonNode e : equipment) {
                Entry entry = entry(e);
                // Two entries for one named slot would display contradictory gear.
                if (entry.slot() != null && !slots.add(entry.slot())) throw invalid("duplicate slot in tab");
                entries.add(entry);
            }
            tabs.add(new Tab(number.intValue(), name, isActive.booleanValue(), List.copyOf(entries)));
        }
        // A non-empty response identifies exactly one active tab; an empty one is legitimate.
        if (!tabs.isEmpty() && active != 1) throw invalid(active == 0 ? "no active tab" : "more than one active tab");
        // The tab number is the in-game position, whatever order the array arrives in.
        tabs.sort(java.util.Comparator.comparingInt(Tab::number));
        return List.copyOf(tabs);
    }

    private static Entry entry(JsonNode e) {
        if (e == null || !e.isObject()) throw invalid("equipment entry is not an object");
        if (!positiveInt(e.get("id"))) throw invalid("equipment item id");
        Integer statId = null;
        Map<String, Integer> attributes = null;
        JsonNode stats = e.get("stats");
        if (stats != null && !stats.isNull()) {
            if (!stats.isObject() || !positiveInt(stats.get("id"))) throw invalid("selected stats");
            statId = stats.get("id").intValue();
            JsonNode attrs = stats.get("attributes");
            if (attrs != null && !attrs.isNull()) {
                if (!attrs.isObject()) throw invalid("selected stat attributes");
                attributes = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
                while (it.hasNext()) {
                    var field = it.next();
                    JsonNode v = field.getValue();
                    if (field.getKey().isBlank() || v == null || !v.isIntegralNumber() || !v.canConvertToInt()
                            || v.intValue() < 0) {
                        throw invalid("selected stat attribute value");
                    }
                    attributes.put(field.getKey(), v.intValue());
                }
            }
        }
        return new Entry(optionalText(e, "slot", "slot"), e.get("id").intValue(),
                optionalText(e, "location", "location"),
                idList(e.get("upgrades"), "upgrades"), idList(e.get("infusions"), "infusions"),
                statId, attributes,
                optionalText(e, "binding", "binding"), optionalText(e, "bound_to", "bound_to"));
    }

    private static List<Integer> idList(JsonNode n, String what) {
        if (n == null || n.isNull()) return List.of();
        if (!n.isArray()) throw invalid(what);
        List<Integer> ids = new ArrayList<>();
        for (JsonNode id : n) {
            if (!positiveInt(id)) throw invalid(what);
            ids.add(id.intValue());
        }
        return List.copyOf(ids);
    }

    private static String optionalText(JsonNode parent, String field, String what) {
        JsonNode v = parent.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw invalid(what);
        return v.asText();
    }

    private static boolean positiveInt(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.intValue() > 0;
    }

    private static IllegalStateException invalid(String what) {
        return new IllegalStateException("Malformed equipment tabs response: " + what);
    }
}
