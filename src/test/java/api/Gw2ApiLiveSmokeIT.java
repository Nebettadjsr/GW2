package api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 4 (docs/TEST_STRATEGY.md §31.4) — optional, explicitly-invoked smoke tests against the
 * real, live Guild Wars 2 API. Only public, unauthenticated endpoints are used, so no API key is
 * required.
 *
 * <p>Named with an "IT" suffix (not "Test") so Surefire's default include patterns
 * ({@code **&#47;*Test.java}, {@code **&#47;Test*.java}, {@code **&#47;*Tests.java},
 * {@code **&#47;*TestCase.java}) never pick it up — {@code ./mvnw test} does not compile-and-skip
 * it, it simply never selects it. To run it on demand against the live API:
 * {@code ./mvnw test -Dtest=Gw2ApiLiveSmokeIT}
 *
 * <p>These tests are allowed to fail (or throw on connection errors) when the live API or network
 * is unreachable — that is expected for this test category (§31.4) and must not be worked around.
 * They are not a substitute for the deterministic, fixture-based Layer 3 parser tests (e.g.
 * {@code parser.RecipeParserTest}), which remain the authoritative, always-run coverage.
 */
class Gw2ApiLiveSmokeIT {

    // Recipe 7319 (the same real recipe already captured as a fixture by
    // parser.RecipeParserTest, STORY-SYNC-002) outputs item 46742 (Lump of Mithrillium, not
    // tradable — flagged NoSell/AccountBound) and consumes item 19721 (Glob of Ectoplasm, x1) as
    // an ingredient. 19721 is used for the item-detail and Trading Post lookups below since it is
    // actually tradable, unlike the recipe's own output item.
    private static final int RECIPE_ID = 7319;
    private static final int RECIPE_OUTPUT_ITEM_ID = 46742;
    private static final int ITEM_ID = 19721;

    @Test
    void itemDetailLookupReturnsExpectedItem() throws Exception {
        JsonNode item = Gw2ApiClient.getPublic("https://api.guildwars2.com/v2/items/" + ITEM_ID);

        assertEquals(ITEM_ID, item.get("id").asInt());
        assertFalse(item.get("name").asText().isBlank());
        assertFalse(item.get("type").asText().isBlank());
    }

    @Test
    void recipeDetailLookupReturnsExpectedRecipe() throws Exception {
        JsonNode recipe = Gw2ApiClient.getPublic("https://api.guildwars2.com/v2/recipes/" + RECIPE_ID);

        assertEquals(RECIPE_ID, recipe.get("id").asInt());
        assertEquals(RECIPE_OUTPUT_ITEM_ID, recipe.get("output_item_id").asInt());
        assertTrue(recipe.get("disciplines").isArray());
        assertTrue(recipe.get("disciplines").size() > 0);
    }

    @Test
    void tradingPostPriceLookupReturnsExpectedPrice() throws Exception {
        JsonNode price = Gw2ApiClient.getPublic("https://api.guildwars2.com/v2/commerce/prices/" + ITEM_ID);

        assertEquals(ITEM_ID, price.get("id").asInt());
        assertTrue(price.get("buys").has("unit_price"));
        assertTrue(price.get("sells").has("unit_price"));
        assertTrue(price.get("sells").get("unit_price").asInt() >= 0);
    }
}
