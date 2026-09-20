package uiverify;

import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;

import java.time.Duration;

/**
 * STORY-UI-002 required test: removing the developer loaded-recipe/row/missing-data counter line
 * (KNOWN_PROBLEMS.md §7.7) must not remove genuine user-facing error reporting. Forces a real
 * {@code SQLException} out of {@link CraftingProfitController#reload} by seeding one recipe (so
 * {@code tpRepo.loadTpQuotes} is actually invoked with a non-empty item set) and then dropping
 * {@code tp_prices} out from under it, and asserts the resulting "load failed" message set by
 * {@code CraftingProfitView}'s catch block is still shown below the title.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewErrorStatusIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewErrorStatusIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;

    @BeforeAll
    void seedFixtureSchemaThenBreakIt() throws Exception {
        fixtures = new CraftingUiTestFixtures();

        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Broken DB Hero')");
        fixtures.execute("""
            INSERT INTO character_crafting (character_id, discipline, rating, is_active)
            VALUES (1, 'Chef', 0, true)
            """);
        fixtures.execute("INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines) " +
                "VALUES (1, 100, 1, 0, ARRAY['Chef'])");
        fixtures.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, 200, 2)");
        fixtures.execute("INSERT INTO account_recipes (recipe_id) VALUES (1)");
        fixtures.execute("INSERT INTO items (item_id, name) VALUES (100, 'Widget'), (200, 'Ore')");

        // Breaks the reload mid-flight (tpRepo.loadTpQuotes queries this table with a non-empty
        // item set, since a recipe now exists) so the view's catch block sets a real error
        // message - proving that removing the debug counter line did not also remove it.
        fixtures.execute("DROP TABLE tp_prices");
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
    void reloadFailure_stillShowsUserFacingErrorBelowTitle() {
        clickOn("Crafting Profit Calculator");

        assertTitlePresent();
        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class, Duration.ofSeconds(10));

        JavaFxUiSupport.waitUntil("error status text to appear", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("❌"));

        org.junit.jupiter.api.Assertions.assertTrue(status.getText().contains("DB load failed"),
                "expected the real load-failure message, got: " + status.getText());
    }

    private void assertTitlePresent() {
        org.junit.jupiter.api.Assertions.assertTrue(lookup("Crafting Profit Analyzer").tryQuery().isPresent(),
                "title should remain even when the reload fails");
    }
}
