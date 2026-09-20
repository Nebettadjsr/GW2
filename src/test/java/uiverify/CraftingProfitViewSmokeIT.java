package uiverify;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-UI-001 required smoke test: launches the real application (not a standalone
 * demonstration scene), navigates into the real {@code CraftingProfitView}, activates the real
 * "Refresh" button, and asserts the resulting TableView shows the expected craftable recipe -
 * proving the reusable capability in {@link JavaFxUiSupport}/{@link CraftingUiTestFixtures}
 * against actual application code, not just standalone controls.
 *
 * <p>Data is a small deterministic fixture in a disposable Postgres schema
 * ({@link CraftingUiTestFixtures}) - never the developer's normal account database or the live
 * GW2 API. Fixture: one character ("UI Test Hero") owns 10 unbound units of "UI Test Ore"
 * (item 200); recipe 1 turns 2 Ore into 1 "UI Test Widget" (item 100, Chef, globally unlocked via
 * {@code account_recipes}), which has a real Trading Post sell price so the controller's
 * revenue-based filter does not drop it. With buying disabled and "use own mats" on (both the
 * view's own defaults), the expected result is exactly one visible row with
 * {@code craftableCount = 5} (10 owned Ore / 2 per craft).
 *
 * <p>STORY-DOM-014 removed Crafting Profit's separate Character selector: the Discipline
 * selector's "All" default (DOMAIN_SPEC.md section 2.2.1) now drives a coordinated plan across
 * every synced character with a matching discipline/rating, so the fixture character needs a
 * {@code character_crafting} row for the recipe's discipline or the row would be blocked
 * {@code RECIPE_NOT_ALLOWED} (nobody eligible) before "Craftable" is even computed.
 *
 * <p>Fixture setup/teardown uses {@code @BeforeAll}/{@code @AfterAll} ({@code PER_CLASS} test
 * instance lifecycle), not {@code @BeforeEach}/{@code @AfterEach}: TestFX's
 * {@code ApplicationExtension} calls {@link #start(Stage)} - which launches the real app and
 * triggers its own startup DB reload - from a {@code BeforeEachCallback}, which JUnit5 always
 * runs before a test class's own {@code @BeforeEach} methods. An earlier version of this test
 * used {@code @BeforeEach} and the app's very first auto-reload ran with no test schema/cache
 * override in place yet - i.e. against the developer's real database - before the fixture rows
 * even existed. {@code @BeforeAll} runs before every per-test extension callback, so the schema,
 * seed data and system-property overrides are all in place before {@link #start(Stage)} ever
 * runs.
 *
 * <p>Named with an "IT" suffix (see {@code api.Gw2ApiLiveSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - this needs a real desktop/window session and a reachable
 * Postgres server, which are not guaranteed wherever {@code ./mvnw test} runs. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewSmokeIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewSmokeIT extends ApplicationTest {

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

        // Output item needs a real sell price, or CraftingProfitController drops the row
        // (revenueCopper <= 0 filter) before it ever reaches the table.
        fixtures.execute("INSERT INTO tp_prices (item_id, sell_unit_price, buy_unit_price) VALUES (%d, 500, 400)"
                .formatted(WIDGET_ITEM_ID));

        // 10 unbound Ore owned outright: with "use own mats" on and buying off (both this view's
        // own defaults), craftableCount = floor(10 / 2) = 5.
        fixtures.execute("INSERT INTO account_materials (item_id, count) VALUES (%d, 10)".formatted(ORE_ITEM_ID));
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) throws Exception {
        // Gw2App lives in the unnamed package and so cannot be imported from a named test
        // package; reflection is the only way to reference it from here.
        Class<?> appClass = Class.forName("Gw2App");
        javafx.application.Application app = (javafx.application.Application) appClass.getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void refreshingAllScope_showsExpectedCraftableRowInRealView() throws Exception {
        clickOn("Crafting Profit Calculator");

        // STORY-DOM-014: no separate Character selector exists anymore - the Discipline
        // selector's "All" default already coordinates every synced character (here, just
        // CHARACTER_NAME, seeded with the Chef discipline above), so no selection is needed.
        Button refreshButton = lookup("Refresh").queryButton();
        clickOn(refreshButton);

        TableView<?> table = lookup(".table-view").queryAs(TableView.class);
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));

        List<Object> names = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Item"));
        List<Object> craftable = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Craftable"));

        assertEquals(List.of("UI Test Widget"), names);
        assertEquals(1, craftable.size());
        assertEquals(5, ((Number) craftable.get(0)).intValue(),
                "10 owned Ore / 2 per craft, buying disabled, should yield exactly 5 craftable widgets");

        // STORY-UI-002 / KNOWN_PROBLEMS.md §7.7: the title stays, and the developer
        // loaded-recipe/row/missing-data counter line below it must be gone after a load.
        assertTrue(lookup("Crafting Profit Analyzer").tryQuery().isPresent(), "title should remain");
        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);
        String statusText = computeOnFxThread(status::getText);
        assertFalse(statusText.matches("(?s).*\\brows=\\d+.*"), "status line should not expose row counters: " + statusText);
        assertFalse(statusText.contains("missingLines="), "status line should not expose missing-line counters: " + statusText);
        assertFalse(statusText.contains("missingTp="), "status line should not expose missing-TP counters: " + statusText);
        assertFalse(statusText.contains("zeroBuyPrice="), "status line should not expose zero-buy-price counters: " + statusText);
        assertFalse(statusText.toLowerCase().contains("loaded"), "status line should not expose the loaded-recipe count: " + statusText);

        // Clicking Refresh again (not just the initial load) must not bring the line back.
        clickOn(refreshButton);
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));
        String statusTextAfterRefresh = computeOnFxThread(status::getText);
        assertFalse(statusTextAfterRefresh.matches("(?s).*\\brows=\\d+.*"),
                "status line should not expose row counters after refresh: " + statusTextAfterRefresh);
        assertFalse(statusTextAfterRefresh.toLowerCase().contains("loaded"),
                "status line should not expose the loaded-recipe count after refresh: " + statusTextAfterRefresh);

        Path screenshot = computeOnFxThread(() -> JavaFxUiSupport.captureScreenshot(table,
                Path.of("target", "ui-test-screenshots", "crafting-profit-smoke.png")));
        assertTrue(java.nio.file.Files.size(screenshot) > 0, "captured screenshot should not be empty");
    }

    /** Runs {@code callable} on the FX Application Thread and returns its result, bounded. */
    private <T> T computeOnFxThread(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(callable));
    }
}
