package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RecipeParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode loadFixture(String resourceName) throws Exception {
        try (InputStream in = RecipeParserTest.class.getResourceAsStream(resourceName)) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void parseRecipeExtractsFieldsFromCapturedApiPayload() throws Exception {
        JsonNode recipeJson = loadFixture("/parser/recipe_7319.json");

        RecipeParser.RecipeRow row = RecipeParser.parseRecipe(recipeJson);

        assertEquals(7319, row.recipeId());
        assertEquals("RefinementEctoplasm", row.type());
        assertEquals(46742, row.outputItem());
        assertEquals(1, row.outputCount());
        assertEquals(450, row.minRating());
        assertEquals(5000, row.craftTime());
        assertArrayEquals(
                new String[]{"Leatherworker", "Armorsmith", "Tailor", "Artificer", "Weaponsmith", "Huntsman"},
                row.disciplines()
        );
        assertArrayEquals(new String[]{"AutoLearned"}, row.flags());
        assertEquals("[&CZccAAA=]", row.chatLink());
        assertEquals("[]", row.guildIngredientsJson());
    }

    @Test
    void parseIngredientsExtractsRowsFromCapturedApiPayload() throws Exception {
        JsonNode recipeJson = loadFixture("/parser/recipe_7319.json");

        List<RecipeParser.IngredientRow> rows = RecipeParser.parseIngredients(recipeJson);

        assertEquals(3, rows.size());
        assertEquals(new RecipeParser.IngredientRow(7319, 19684, 50), rows.get(0));
        assertEquals(new RecipeParser.IngredientRow(7319, 19721, 1), rows.get(1));
        assertEquals(new RecipeParser.IngredientRow(7319, 46747, 10), rows.get(2));
    }

    @Test
    void typedPayloadSeparatesItemIngredientsFromCurrencyRequirements() throws Exception {
        JsonNode recipe = MAPPER.readTree("""
            {"id":13839,"type":"Jade","output_item_id":97487,"output_item_count":1,"ingredients":[
              {"type":"Item","id":97102,"count":4},
              {"type":"Item","id":19721,"count":2},
              {"type":"Currency","id":61,"count":30},
              {"type":"Currency","id":50,"count":0},
              {"type":"Mystery","id":7,"count":1}]}
            """);

        List<RecipeParser.IngredientRow> items = RecipeParser.parseIngredients(recipe);
        List<RecipeParser.RequirementRow> others = RecipeParser.parseNonItemRequirements(recipe);

        assertEquals(List.of(new RecipeParser.IngredientRow(13839, 97102, 4),
                new RecipeParser.IngredientRow(13839, 19721, 2)), items);
        assertEquals(List.of(new RecipeParser.RequirementRow(13839, "Currency", 61, 30),
                new RecipeParser.RequirementRow(13839, "Mystery", 7, 1)), others);
    }

    @Test
    void legacyPayloadHasNoNonItemRequirements() throws Exception {
        assertEquals(List.of(), RecipeParser.parseNonItemRequirements(loadFixture("/parser/recipe_7319.json")));
    }

    @Test
    void typedEntriesReadOnlyCanonicalIdAndLegacyEntriesReadOnlyItemId() throws Exception {
        JsonNode recipe = MAPPER.readTree("""
            {"id":5,"ingredients":[
              {"type":"Item","id":10,"item_id":0,"count":1},
              {"type":"Item","id":11,"item_id":999,"count":2},
              {"type":"Currency","id":61,"item_id":12,"count":30},
              {"item_id":13,"id":888,"count":3},
              {"item_id":0,"id":14,"count":4},
              {"type":"Item","id":0,"item_id":15,"count":5},
              {"type":"Item","item_id":16,"count":6}]}
            """);

        assertEquals(List.of(new RecipeParser.IngredientRow(5, 10, 1), new RecipeParser.IngredientRow(5, 11, 2),
                new RecipeParser.IngredientRow(5, 13, 3)), RecipeParser.parseIngredients(recipe));
        assertEquals(List.of(new RecipeParser.RequirementRow(5, "Currency", 61, 30)),
                RecipeParser.parseNonItemRequirements(recipe));
    }
}
