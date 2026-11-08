package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Slim public equipment details from {@code /v2/items} and {@code /v2/itemstats} (STORY-CHAR-014).
 * A malformed record returns null so a caller can keep any previously stored good row. Upstream text
 * is reduced to plain text: GW2 markup tags are stripped and never passed on as HTML.
 */
public final class EquipmentDetailsParser {
    private EquipmentDetailsParser() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int MAX_TEXT = 600;
    private static final int MAX_LINES = 24;

    /** @return {id, details} stored form, or null when the item is malformed */
    public static ObjectNode item(JsonNode it) {
        if (it == null || !it.isObject() || !it.path("id").canConvertToInt() || it.path("id").asInt() <= 0) return null;
        ObjectNode out = F.objectNode();
        out.put("itemId", it.get("id").asInt());
        text(out, "description", it.get("description"));
        if (it.path("level").canConvertToInt()) out.put("level", it.get("level").asInt());
        if (it.path("vendor_value").canConvertToInt()) out.put("vendorValue", it.get("vendor_value").asInt());
        JsonNode d = it.get("details");
        ArrayNode effects = F.arrayNode();
        ArrayNode bonuses = F.arrayNode();
        ObjectNode attrs = F.objectNode();
        if (d != null && d.isObject()) {
            if (d.hasNonNull("type")) out.put("subtype", d.get("type").asText());
            if (d.hasNonNull("weight_class")) out.put("weightClass", d.get("weight_class").asText());
            if (d.path("defense").canConvertToInt()) out.put("defense", d.get("defense").asInt());
            if (d.path("min_power").canConvertToInt()) out.put("minPower", d.get("min_power").asInt());
            if (d.path("max_power").canConvertToInt()) out.put("maxPower", d.get("max_power").asInt());
            JsonNode infix = d.get("infix_upgrade");
            if (infix != null && infix.isObject()) {
                if (infix.path("id").canConvertToInt()) out.put("statId", infix.get("id").asInt());
                JsonNode list = infix.get("attributes");
                if (list != null) {
                    if (!list.isArray()) return null;
                    for (JsonNode a : list) {
                        if (!a.hasNonNull("attribute") || !a.path("modifier").canConvertToInt()) return null;
                        attrs.put(a.get("attribute").asText(), a.get("modifier").asInt());
                    }
                }
                JsonNode buff = infix.get("buff");
                if (buff != null && buff.hasNonNull("description")) lines(effects, buff.get("description").asText());
            }
            if (d.path("attribute_adjustment").isNumber()) {
                out.put("attributeAdjustment", d.get("attribute_adjustment").asDouble());
            }
            JsonNode b = d.get("bonuses");
            if (b != null) {
                if (!b.isArray()) return null;
                for (JsonNode x : b) if (x.isTextual() && bonuses.size() < MAX_LINES) lines(bonuses, x.asText(), 1);
            }
        }
        out.set("attributes", attrs);
        out.set("effects", effects);
        out.set("runeBonuses", bonuses);
        return out;
    }

    /** @return {name, attributes:[{attribute,multiplier,value}]} or null when malformed */
    public static ObjectNode stat(JsonNode s) {
        if (s == null || !s.isObject() || !s.path("id").canConvertToInt() || s.path("id").asInt() <= 0) return null;
        JsonNode list = s.get("attributes");
        if (list == null || !list.isArray()) return null;
        ObjectNode out = F.objectNode();
        out.put("statId", s.get("id").asInt());
        ArrayNode arr = out.putArray("attributes");
        for (JsonNode a : list) {
            if (!a.hasNonNull("attribute") || !a.path("multiplier").isNumber()) return null;
            ObjectNode x = arr.addObject();
            x.put("attribute", a.get("attribute").asText());
            x.put("multiplier", a.get("multiplier").asDouble());
            x.put("value", a.path("value").isNumber() ? a.get("value").asInt() : 0);
        }
        return out;
    }

    /** Plain text: markup tags removed, whitespace collapsed, bounded. */
    public static String plain(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", "").replace("<", "").replace(">", "");
        t = t.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("[ \\t]+", " ").strip();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    private static void text(ObjectNode out, String field, JsonNode v) {
        if (v == null || !v.isTextual()) return;
        String t = plain(v.asText());
        if (t != null && !t.isEmpty()) out.put(field, t);
    }

    private static void lines(ArrayNode into, String raw, int max) {
        String t = plain(raw);
        if (t == null) return;
        for (String line : t.split("\n")) {
            String l = line.strip();
            if (!l.isEmpty() && into.size() < MAX_LINES && max > 0) into.add(l);
        }
    }

    private static void lines(ArrayNode into, String raw) { lines(into, raw, 1); }
}
