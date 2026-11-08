package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Optional public artwork and tooltip data, retained separately from build placement facts. */
public final class BuildDisplayDetails {
    private BuildDisplayDetails() {}

    public static String capture(JsonNode source) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String field : new String[]{"background", "description"}) {
            if (source.path(field).isTextual()) out.put(field, source.get(field).asText());
        }
        if (source.path("facts").isArray()) out.set("facts", source.get("facts"));
        return out.toString();
    }
}
