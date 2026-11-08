package web.dto;

import application.icons.ResourceIconUrls;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.BuildResourceType;
import parser.EquipmentDetailsParser;

import java.util.ArrayList;
import java.util.List;

/** Player-facing descriptions and facts; no invented damage totals or inferred trait effects. */
public final class BuildReferenceDetails {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private BuildReferenceDetails() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fact(String text, String value, String iconUrl) {}
    public record Details(String description, List<Fact> facts) {}

    public static JsonNode read(String json) {
        if (json == null) return MAPPER.createObjectNode();
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : MAPPER.createObjectNode();
        }
        catch (java.io.IOException invalidCache) { return MAPPER.createObjectNode(); }
    }

    public static Details of(BuildResourceType type, int id, String json) {
        JsonNode details = read(json);
        List<Fact> facts = new ArrayList<>();
        JsonNode raw = details.path("facts");
        for (int i = 0; i < raw.size(); i++) {
            JsonNode fact = raw.get(i);
            String text = plain(fact.path("text").asText(null));
            if (text == null || text.isBlank()) continue;
            facts.add(new Fact(text, value(fact), ResourceIconUrls.imageUrlFor(type, id,
                    "fact-" + i, fact.path("icon").asText(null))));
        }
        return new Details(plain(details.path("description").asText(null)), facts.isEmpty() ? null : List.copyOf(facts));
    }

    private static String value(JsonNode f) {
        return switch (f.path("type").asText()) {
            case "Percent" -> number(f, "percent", "%");
            case "Time" -> number(f, "duration", " s");
            case "Recharge" -> number(f, "value", " s");
            case "Distance" -> number(f, "distance", "");
            case "Number", "Range", "AttributeAdjust" -> number(f, "value", "");
            case "Buff", "PrefixedBuff" -> buff(f);
            case "ComboField" -> plain(f.path("field_type").asText(null));
            case "ComboFinisher" -> join(plain(f.path("finisher_type").asText(null)), number(f, "percent", "%"));
            // Damage facts give coefficients, not damage totals. Only an explicit hit count is displayed.
            case "Damage" -> number(f, "hit_count", f.path("hit_count").asInt() == 1 ? " hit" : " hits");
            default -> null;
        };
    }

    private static String buff(JsonNode f) {
        String status = plain(f.path("status").asText(null));
        String duration = number(f, "duration", " s");
        String count = f.path("apply_count").asInt() > 1 ? number(f, "apply_count", " stacks") : null;
        return join(join(status, duration), count);
    }

    private static String number(JsonNode f, String field, String suffix) {
        return f.path(field).isNumber() ? f.get(field).decimalValue().stripTrailingZeros().toPlainString() + suffix : null;
    }

    private static String join(String a, String b) { return a == null ? b : b == null ? a : a + " · " + b; }
    private static String plain(String raw) { return EquipmentDetailsParser.plain(raw); }
}
