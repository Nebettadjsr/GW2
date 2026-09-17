package parser;

import com.fasterxml.jackson.databind.JsonNode;

public final class RecipeIdParser {
    private RecipeIdParser() {}

    public static Integer parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        int id = node.asInt(0);
        return id > 0 ? id : null;
    }
}