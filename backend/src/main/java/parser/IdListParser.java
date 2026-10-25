package parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class IdListParser {
    private IdListParser() {}

    /**
     * Parses a JSON array that contains integer IDs.
     * Example: /v2/recipes returns [1,2,3,...]
     */
    public static List<Integer> parseIntArray(JsonNode root, String context) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Unexpected JSON (" + context + "): not an array");
        }

        List<Integer> out = new ArrayList<>(root.size());
        for (JsonNode n : root) {
            if (n == null || n.isNull()) continue;

            // /v2/recipes returns numbers directly; asInt() is safe here
            int id = n.asInt(-1);
            if (id > 0) out.add(id);
        }
        return out;
    }
}