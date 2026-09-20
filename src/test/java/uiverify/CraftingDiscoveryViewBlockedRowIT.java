package uiverify;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.util.WaitForAsyncUtils;
import org.testfx.framework.junit5.ApplicationTest;
import repo.DiscChoice;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-DOM-013 real-view regression, Discovery counterpart of {@link CraftingProfitViewBlockedRowIT}:
 * a required purchase with no usable Trading Post quote must keep its row visible in the real
 * {@code CraftingDiscoveryView} table with the {@code PRICE_UNAVAILABLE} blocked reason, instead
 * of being silently dropped. Reuses {@link CraftingUiTestFixtures}/{@link JavaFxUiSupport}
 * established by STORY-UI-001.
 *
 * <p>Fixture: recipe 1 turns 2 "UI Test Ore" (item 200, no Trading Post quote at all) into 1
 * "UI Test Widget" (item 100, Chef, real sell price), not yet unlocked on the account or the
 * character - i.e. a genuine discoverable/missing recipe (DOMAIN_SPEC.md section 34). The
 * character has a Chef crafting entry high enough to see it, and owns no Ore. Discovery's own
 * "buy missing mats" default is already enabled.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - needs a real desktop/window session and Postgres. Run
 * explicitly: {@code ./mvnw test -Dtest=CraftingDiscoveryViewBlockedRowIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingDiscoveryViewBlockedRowIT extends ApplicationTest {

    private static final int ORE_ITEM_ID = 200;
    private static final int WIDGET_ITEM_ID = 100;
    private static final String CHARACTER_NAME = "UI Test Hero";
    private static final String DISCIPLINE = "Chef";

    private CraftingUiTestFixtures fixtures;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();

        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, '" + CHARACTER_NAME + "')");
        fixtures.execute("""
            INSERT INTO character_crafting (character_id, discipline, rating, is_active)
            VALUES (1, '%s', 400, true)
            """.formatted(DISCIPLINE));

        fixtures.execute("""
            INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
            VALUES (1, %d, 1, 0, ARRAY['%s'])
            """.formatted(WIDGET_ITEM_ID, DISCIPLINE));
        fixtures.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, %d, 2)"
                .formatted(ORE_ITEM_ID));
        // Deliberately NOT inserted into account_recipes/character_recipes: this recipe must
        // remain a genuine "missing discoverable recipe" for CraftingDiscoveryController to
        // consider it at all (repo.RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter).

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
        clickOn("Crafting Discover Helper");

        // Discovery has three ComboBoxes (discipline+char, character, sort); wait for all of them
        // to actually exist in the scene before trying to tell them apart, since the "Crafting
        // Discover Helper" navigation and this view's construction are asynchronous relative to
        // clickOn(...) returning.
        JavaFxUiSupport.waitUntil("all three ComboBoxes to be constructed",
                Duration.ofSeconds(10),
                () -> lookup(".combo-box").queryAllAs(ComboBox.class).size() >= 3);

        @SuppressWarnings("unchecked")
        List<ComboBox> comboBoxes = new java.util.ArrayList<>(lookup(".combo-box").queryAllAs(ComboBox.class));

        @SuppressWarnings("unchecked")
        ComboBox<String> characterBox = comboBoxes.stream()
                .filter(cb -> "Character".equals(cb.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ComboBox with prompt text \"Character\" found"));

        JavaFxUiSupport.waitUntil("character ComboBox to be populated from the fixture schema",
                Duration.ofSeconds(10),
                () -> !characterBox.getItems().isEmpty());

        // The remaining non-"Character" ComboBox is either the discipline+char selector (starts
        // empty, then filled with DiscChoice entries) or the sort ComboBox (never empty, always
        // holds fixed String entries) - excluding the latter identifies the former.
        @SuppressWarnings("unchecked")
        ComboBox<DiscChoice> disciplineBox = comboBoxes.stream()
                .filter(cb -> cb != characterBox)
                .filter(cb -> cb.getItems().isEmpty()
                        || cb.getItems().stream().findFirst().orElse(null) instanceof DiscChoice)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Discipline+Char ComboBox found"));

        JavaFxUiSupport.waitUntil("discipline+character ComboBox to be populated from the fixture schema",
                Duration.ofSeconds(10),
                () -> !disciplineBox.getItems().isEmpty());

        interact(() -> {
            disciplineBox.getSelectionModel().selectFirst();
            characterBox.getSelectionModel().select(CHARACTER_NAME);
        });
        assertEquals(CHARACTER_NAME, characterBox.getValue());

        Button refreshButton = lookup("Refresh").queryButton();
        clickOn(refreshButton);

        TableView<?> table = lookup(".table-view").queryAs(TableView.class);
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));

        JavaFxUiSupport.waitUntil("status column to report PRICE_UNAVAILABLE",
                Duration.ofSeconds(10),
                () -> String.valueOf(computeOnFxThread(
                        () -> JavaFxUiSupport.columnValues(table, "Status / requirements")).get(0))
                        .contains("PRICE_UNAVAILABLE"));

        List<Object> names = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Item"));
        List<Object> status = computeOnFxThread(() -> JavaFxUiSupport.columnValues(table, "Status / requirements"));

        assertEquals(List.of("UI Test Widget"), names,
                "Row with the unavailable required purchase must remain visible, not be dropped");
        assertTrue(String.valueOf(status.get(0)).contains("PRICE_UNAVAILABLE"),
                "Status column must expose the PRICE_UNAVAILABLE blocked reason, actual: " + status.get(0));
    }

    private <T> T computeOnFxThread(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(callable));
    }
}
