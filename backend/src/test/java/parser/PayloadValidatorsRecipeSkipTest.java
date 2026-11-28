package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shared skip rule for ingredient-less item recipes (upstream placeholders 13373 and 13390). */
class PayloadValidatorsRecipeSkipTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String P13373 = "{\"id\":13373,\"type\":\"Component\",\"output_item_id\":94257,\"output_item_count\":1,"
            + "\"time_to_craft_ms\":1000,\"disciplines\":[],\"min_rating\":400,\"flags\":[\"LearnedFromItem\"],"
            + "\"ingredients\":[],\"chat_link\":\"[&CT00AAA=]\"}";
    private static final String P13390 = P13373.replace("13373", "13390");

    private static JsonNode j(String s) {
        try { return JSON.readTree(s); } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test
    void placeholdersAreSkippedButStillRejectedByTheStrictValidator() {
        for (String p : new String[] {P13373, P13390, P13373.replace("\"ingredients\":[],", ""),
                P13373.replace("\"ingredients\":[]", "\"ingredients\":null")}) {
            assertTrue(PayloadValidators.isIngredientlessItemRecipe(j(p)), p);
            assertThrows(IllegalStateException.class, () -> PayloadValidators.requireRecipe(j(p)), p);
        }
    }

    @Test
    void otherMalformedOrOrdinaryRecordsAreNotSkipped() {
        String[] notSkipped = {
                P13373.replace("\"min_rating\":400", "\"min_rating\":-1"),
                P13373.replace("\"output_item_count\":1", "\"output_item_count\":0"),
                P13373.replace("\"id\":13373", "\"id\":0"),
                P13373.replace("\"ingredients\":[]", "\"ingredients\":\"x\""),
                P13373.replace("\"ingredients\":[]", "\"ingredients\":[{\"item_id\":1,\"count\":0}]"),
                P13373.replace("\"ingredients\":[]", "\"ingredients\":[{\"item_id\":1,\"count\":1}]"),
                P13373.replace("\"output_item_id\":94257", "\"output_upgrade_id\":3"),
                "7", "null"};
        for (String r : notSkipped) assertFalse(PayloadValidators.isIngredientlessItemRecipe(j(r)), r);
        assertFalse(PayloadValidators.isIngredientlessItemRecipe(null));
    }
}
