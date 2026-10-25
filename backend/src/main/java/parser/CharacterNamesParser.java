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

    /**
     * Validates a complete roster response for replacement: every entry must be a non-blank string.
     * Unlike {@link #parse}, nothing is filtered, so a malformed entry cannot turn into a false
     * empty or partial roster; a genuine {@code []} still yields an empty list.
     */
    public static List<String> parseStrict(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Unexpected JSON (characters): not an array");
        }
        List<String> out = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Unexpected JSON (characters): malformed name entry; roster not published");
            }
            out.add(n.asText());
        }
        return out;
    }
}
