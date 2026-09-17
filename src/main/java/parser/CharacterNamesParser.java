package parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class CharacterNamesParser {
    private CharacterNamesParser() {}

    /** Parses /v2/characters response (array of strings) into a List<String>. */
    public static List<String> parse(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Unexpected JSON (characters): not an array");
        }

        List<String> out = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            if (n == null || n.isNull()) continue;
            String name = n.asText(null);
            if (name != null && !name.isBlank()) out.add(name);
        }
        return out;
    }
}