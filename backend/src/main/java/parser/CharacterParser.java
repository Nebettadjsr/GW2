package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterInfo;

public final class CharacterParser {
    private CharacterParser() {}

    public static CharacterInfo parseInfo(JsonNode c) {
        if (c == null || c.isNull()) return null;

        String name = c.path("name").asText(null);
        if (name == null || name.isBlank()) return null;

        String profession = c.path("profession").asText(null);
        String race = c.path("race").asText(null);
        String gender = c.path("gender").asText(null);

        Integer level = c.hasNonNull("level") ? c.get("level").asInt() : null;
        String createdIso = c.hasNonNull("created") ? c.get("created").asText() : null;

        long age = requiredCounter(c, name, "age");
        long deaths = requiredCounter(c, name, "deaths");

        return new CharacterInfo(name, profession, race, gender, level, createdIso, age, deaths);
    }

    /** Exact integral value in 0..Long.MAX_VALUE; anything else (missing, null, text, fraction, overflow) is rejected. */
    private static long requiredCounter(JsonNode c, String name, String field) {
        JsonNode v = c.get(field);
        if (v == null || v.isNull() || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) {
            throw new IllegalStateException("Malformed '" + field + "' for character '" + name
                    + "'; roster not published");
        }
        return v.longValue();
    }

    public static JsonNode craftingNode(JsonNode characterRoot) {
        return characterRoot == null ? null : characterRoot.get("crafting");
    }

    public static JsonNode recipesNode(JsonNode characterRoot) {
        return characterRoot == null ? null : characterRoot.get("recipes");
    }

    public static JsonNode bagsNode(JsonNode characterRoot) {
        return characterRoot == null ? null : characterRoot.get("bags");
    }

    public static JsonNode equipmentNode(JsonNode characterRoot) {
        return characterRoot == null ? null : characterRoot.get("equipment");
    }
}