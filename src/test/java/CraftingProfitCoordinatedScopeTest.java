import craft.CraftingSettings;
import org.junit.jupiter.api.Test;
import repo.DiscChoice;
import uiverify.CraftingUiTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingProfitCoordinatedScopeTest {
    @Test
    void specificCharacterUsesStoredRatingAndOnlyItsOwnBoundMaterials() throws Exception {
        try (var fixture = new CraftingUiTestFixtures()) {
            fixture.execute("INSERT INTO characters VALUES (1, 'Novice'), (2, 'Expert')");
            fixture.execute("INSERT INTO character_crafting (character_id, discipline, rating) VALUES (1, 'Chef', 1), (2, 'Chef', 400)");
            fixture.execute("INSERT INTO recipes VALUES (1, 100, 1, 400, ARRAY['Chef'], ARRAY[]::text[])");
            fixture.execute("INSERT INTO account_recipes VALUES (1)");
            fixture.execute("INSERT INTO recipe_ingredients VALUES (1, 200, 2)");
            fixture.execute("INSERT INTO account_bank (slot, item_id, count, binding, bound_to) VALUES (0, 200, 2, 'Character', 'Novice'), (1, 200, 4, 'Character', 'Expert')");
            fixture.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) VALUES (100, 500, 600)");
            var settings = new CraftingSettings(true, false, 0, false, false, false);
            var controller = new CraftingProfitController();
            assertEquals(0, controller.reload(DiscChoice.charDiscipline("Chef", 500, "Novice"), settings).getFirst().craftableCount);
            assertEquals(2, controller.reload(DiscChoice.charDiscipline("Chef", 400, "Expert"), settings).getFirst().craftableCount);
            assertEquals(2, controller.reload(DiscChoice.all(), settings).getFirst().craftableCount);
        }
    }
}
