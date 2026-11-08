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

    /** Stored-row format; rows of another version or without the required facts are retried. */
    public static final int FORMAT = 2;
    private static final java.util.Set<String> GEAR = java.util.Set.of("Armor", "Weapon", "Trinket", "Back");

    private static boolean isInt(JsonNode n) { return n != null && n.isIntegralNumber() && n.canConvertToInt(); }

    /** A present field must be well-formed; absent is fine. */
    private static boolean badInt(JsonNode parent, String f) {
        return parent.has(f) && !parent.get(f).isNull() && !isInt(parent.get(f));
    }

    private static boolean badText(JsonNode parent, String f) {
        return parent.has(f) && !parent.get(f).isNull() && !parent.get(f).isTextual();
    }

    /**
     * @return stored form, or null when the record is malformed or lacks the facts its type needs
     * (a bare {@code {"id":1}} is not a complete record)
     */
    public static ObjectNode item(JsonNode it) {
        if (it == null || !it.isObject() || !isInt(it.get("id")) || it.get("id").asInt() <= 0) return null;
        if (badInt(it, "level") || badInt(it, "vendor_value") || badText(it, "description")) return null;
        if (!it.hasNonNull("type") || !it.get("type").isTextual() || it.get("type").asText().isBlank()) return null;
        String type = it.get("type").asText();
        ObjectNode out = F.objectNode();
        out.put("format", FORMAT);
        out.put("itemId", it.get("id").asInt());
        out.put("itemType", type);
        text(out, "description", it.get("description"));
        if (isInt(it.get("level"))) out.put("level", it.get("level").asInt());
        if (isInt(it.get("vendor_value"))) out.put("vendorValue", it.get("vendor_value").asInt());
        JsonNode d = it.get("details");
        if (d != null && !d.isNull() && !d.isObject()) return null;
        boolean upgrade = "UpgradeComponent".equals(type);
        if ((GEAR.contains(type) || upgrade) && (d == null || !d.isObject())) return null;
        if (!GEAR.contains(type) && !upgrade && !out.has("description")) return null;
        ArrayNode effects = F.arrayNode();
        ArrayNode bonuses = F.arrayNode();
        ObjectNode attrs = F.objectNode();
        if (d != null && d.isObject()) {
            if (badText(d, "type") || badText(d, "weight_class") || badInt(d, "defense")
                    || badInt(d, "min_power") || badInt(d, "max_power")) return null;
            if (d.has("attribute_adjustment") && !d.get("attribute_adjustment").isNull()
                    && !d.get("attribute_adjustment").isNumber()) return null;
            if (("Armor".equals(type) || "Weapon".equals(type) || upgrade) && !d.hasNonNull("type")) return null;
            if ("Armor".equals(type) && (!d.hasNonNull("weight_class") || !isInt(d.get("defense")))) return null;
            if (d.hasNonNull("type")) out.put("subtype", d.get("type").asText());
            if (d.hasNonNull("weight_class")) out.put("weightClass", d.get("weight_class").asText());
            if (isInt(d.get("defense"))) out.put("defense", d.get("defense").asInt());
            if (isInt(d.get("min_power"))) out.put("minPower", d.get("min_power").asInt());
            if (isInt(d.get("max_power"))) out.put("maxPower", d.get("max_power").asInt());
            JsonNode infix = d.get("infix_upgrade");
            if (infix != null && !infix.isNull()) {
                if (!infix.isObject() || badInt(infix, "id")) return null;
                if (isInt(infix.get("id"))) out.put("statId", infix.get("id").asInt());
                JsonNode list = infix.get("attributes");
                if (list != null && !list.isNull()) {
                    if (!list.isArray()) return null;
                    for (JsonNode a : list) {
                        if (!a.isObject() || !a.hasNonNull("attribute") || !a.get("attribute").isTextual()
                                || !isInt(a.get("modifier"))) return null;
                        attrs.put(a.get("attribute").asText(), a.get("modifier").asInt());
                    }
                }
                JsonNode buff = infix.get("buff");
                if (buff != null && !buff.isNull()) {
                    if (!buff.isObject() || badText(buff, "description")) return null;
                    if (buff.hasNonNull("description")) lines(effects, buff.get("description").asText());
                }
            }
            if (d.path("attribute_adjustment").isNumber()) {
                out.put("attributeAdjustment", d.get("attribute_adjustment").asDouble());
            }
            JsonNode b = d.get("bonuses");
            if (b != null && !b.isNull()) {
                if (!b.isArray()) return null;
                for (JsonNode x : b) {
                    if (!x.isTextual()) return null;
                    if (bonuses.size() < MAX_LINES) lines(bonuses, x.asText(), 1);
                }
            }
            if ("Rune".equals(out.path("subtype").asText()) && bonuses.isEmpty()) return null;
        }
        out.set("attributes", attrs);
        out.set("effects", effects);
        out.set("runeBonuses", bonuses);
        return out;
    }

    /** The one predicate for "a stored details row is complete": current format and its required facts. */
    public static boolean complete(JsonNode stored) {
        return stored != null && stored.isObject() && stored.path("format").asInt(0) == FORMAT
                && isInt(stored.get("itemId")) && stored.path("itemType").isTextual()
                && stored.path("attributes").isObject() && stored.path("effects").isArray()
                && stored.path("runeBonuses").isArray();
    }

    /** @return {name, attributes:[{attribute,multiplier,value}]} or null when malformed */
    public static ObjectNode stat(JsonNode s) {
        if (s == null || !s.isObject() || !isInt(s.get("id")) || s.get("id").asInt() <= 0) return null;
        JsonNode list = s.get("attributes");
        if (list == null || !list.isArray() || list.isEmpty()) return null;
        ObjectNode out = F.objectNode();
        out.put("statId", s.get("id").asInt());
        ArrayNode arr = out.putArray("attributes");
        for (JsonNode a : list) {
            if (!a.isObject() || !a.hasNonNull("attribute") || !a.get("attribute").isTextual()
                    || !a.path("multiplier").isNumber()) return null;
            if (a.has("value") && !a.get("value").isNull() && !isInt(a.get("value"))) return null;
            ObjectNode x = arr.addObject();
            x.put("attribute", a.get("attribute").asText());
            x.put("multiplier", a.get("multiplier").asDouble());
            x.put("value", isInt(a.get("value")) ? a.get("value").asInt() : 0);
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
