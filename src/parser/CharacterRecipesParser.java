package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterRecipeRow;

import java.util.ArrayList;
import java.util.List;

public final class CharacterRecipesParser {
    private CharacterRecipesParser() {}

    public static List<CharacterRecipeRow> parse(JsonNode recipesArr) {
        List<CharacterRecipeRow> out = new ArrayList<>();
        if (recipesArr == null || recipesArr.isNull() || !recipesArr.isArray()) return out;

        for (JsonNode idNode : recipesArr) {
            if (idNode == null || idNode.isNull()) continue;
            int recipeId = idNode.asInt(0);
            if (recipeId <= 0) continue;
            out.add(new CharacterRecipeRow(recipeId));
        }
        return out;
    }
}