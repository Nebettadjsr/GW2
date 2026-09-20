package uiverify;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-DOM-013 real-view regression: a required purchase with no usable Trading Post quote must
 * keep its row visible in the real {@code CraftingProfitView} table, carrying the
 * {@code PRICE_UNAVAILABLE} blocked reason, instead of being silently dropped (the documented
 * {@code hasZeroPricedBuy}/{@code revenueCopper <= 0} controller-level filtering in
 * {@code docs/KNOWN_PROBLEMS.md} section 3.5). Reuses {@link CraftingUiTestFixtures}/
 * {@link JavaFxUiSupport} established by STORY-UI-001.
 *
 * <p>Fixture: recipe 1 turns 2 "UI Test Ore" (item 200, no Trading Post quote at all) into 1
 * "UI Test Widget" (item 100, Chef, globally unlocked, real sell price). The character owns no
 * Ore. With "buy missing mats" enabled, the controller must still surface the row (0 craftable)
 * with a status of "PRICE_UNAVAILABLE" rather than removing it.
 *
 * <p>STORY-DOM-014 removed Crafting Profit's separate Character selector: the Discipline
 * selector's "All" default (DOMAIN_SPEC.md section 2.2.1) now drives a coordinated plan across
 * every synced character with a matching discipline/rating, so the fixture character needs a
 * {@code character_crafting} row for the recipe's discipline or the row would be blocked
 * {@code RECIPE_NOT_ALLOWED} (nobody eligible) before buying is even considered.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - needs a real desktop/window session and Postgres. Run
 * explicitly: {@code ./mvnw test -Dtest=CraftingProfitViewBlockedRowIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewBlockedRowIT extends ApplicationTest {

    private static final int ORE_ITEM_ID = 200;
    private static final int WIDGET_ITEM_ID = 100;
    private static final String CHARACTER_NAME = "UI Test Hero";

    private CraftingUiTestFixtures fixtures;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();

        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, '" + CHARACTER_NAME + "')");
        fixtures.execute("""
            INSERT INTO character_crafting (character_id, discipline, rating, is_active)
            VALUES (1, 'Chef', 0, true)
            """);

        fixtures.execute("""
            INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
            VALUES (1, %d, 1, 0, ARRAY['Chef'])
            """.formatted(WIDGET_ITEM_ID));
        fixtures.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, %d, 2)"
                .formatted(ORE_ITEM_ID));
        fixtures.execute("INSERT INTO account_recipes (recipe_id) VALUES (1)");

        fixtures.execute("INSERT INTO items (item_id, name) VALUES (%d, 'UI Test Widget')".formatted(WIDGET_ITEM_ID));
        fixtures.execute("INSERT INTO items (item_id, name) VALUES (%d, 'UI Test Ore')".formatted(ORE_ITEM_ID));

        // Widget needs a real sell price so revenueCopper > 0.
        fixtures.execute("INSERT INTO tp_prices (item_id, sell_unit_price, buy_unit_price) VALUES (%d, 500, 400)"
                .formatted(WIDGET_ITEM_ID));

        // Deliberately no tp_prices row for the Ore, and no owned Ore: the required 2-Ore
        // purchase has no usable quote at all (DOMAIN_SPEC.md section 21).
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) throws Exception {
        Class<?> appClass = Class.forName("Gw2App");
        javafx.application.Application app = (javafx.application.Application) appClass.getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void requiredUnpricedPurchaseStaysVisibleWithBlockedReasonInRealView() throws Exception {
        clickOn("Crafting Profit Calculator");

        // STORY-DOM-014: no separate Character selector exists anymore - the Discipline
        // selector's "All" default already coordinates every synced character (here, just
        // CHARACTER_NAME, seeded with the Chef discipline above), so no selection is needed.
        TableView<?> table = lookup(".table-view").queryAs(TableView.class);
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));

        CheckBox allowBuyCheck = lookup("buy missing mats").queryAs(CheckBox.class);
        clickOn(allowBuyCheck);
        assertTrue(allowBuyCheck.isSelected());

        // Enabling "buy missing mats" triggers its own async reload (CraftingProfitView's
        // allowBuyCheck listener); the row already present from the pre-toggle reload (blocked
        // as BUYING_DISABLED) must not be mistaken for the post-toggle result.
        JavaFxUiSupport.waitUntil("status column to report PRICE_UNAVAILABLE after enabling buying",
                Duration.ofSeconds(10),
                () -> String.valueOf(computeOnFxThread(
                        () -> JavaFxUiSupport.columnValues(table, "Status / requirements")).get(0))
                        .contains("PRICE_UNAVAILABLE"));

        List<Object> names = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Item"));
        List<Object> craftable = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Craftable"));
        List<Object> status = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Status / requirements"));

        assertEquals(List.of("UI Test Widget"), names,
                "Row with the unavailable required purchase must remain visible, not be dropped");
        assertEquals(0, ((Number) craftable.get(0)).intValue(),
                "No Ore owned and no usable quote to buy it: 0 craftable");
        assertTrue(String.valueOf(status.get(0)).contains("PRICE_UNAVAILABLE"),
                "Status column must expose the PRICE_UNAVAILABLE blocked reason, actual: " + status.get(0));
    }

    private <T> T computeOnFxThread(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(callable));
    }
}
