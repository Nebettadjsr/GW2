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

        return new CharacterInfo(name, profession, race, gender, level, createdIso);
    }
}