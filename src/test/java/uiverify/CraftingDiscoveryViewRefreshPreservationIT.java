package uiverify;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import repo.DiscChoice;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * STORY-DOM-015 real-view regression for {@code DOMAIN_SPEC.md} section 2.2.1, Discovery
 * counterpart of {@link CraftingProfitViewRefreshPreservationIT}: a manual "Refresh" must reload
 * using the user's current Character selection, Discipline+Character scope, sort mode and search
 * text instead of resetting any of them, and a Character change made after a Refresh must still
 * reach the displayed calculation (the Character selector - independent of the Discipline+Char
 * scope - drives whose owned inventory is used, per {@code CraftingDiscoveryController.reload}).
 * Reuses {@link CraftingUiTestFixtures}/{@link JavaFxUiSupport} (STORY-UI-001).
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryViewRefreshPreservationIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingDiscoveryViewRefreshPreservationIT extends ApplicationTest {

    private static final int ORE_ITEM_ID = 200;
    private static final int WIDGET_ITEM_ID = 100;

    private CraftingUiTestFixtures fixtures;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();
        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Alice'), (2, 'Bea')");
        fixtures.execute("INSERT INTO character_crafting (character_id, discipline, rating, is_active) VALUES (1, 'Chef', 400, true)");
        fixtures.execute("""
            INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
            VALUES (1, %d, 1, 0, ARRAY['Chef'])
            """.formatted(WIDGET_ITEM_ID));
        fixtures.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, %d, 2)"
                .formatted(ORE_ITEM_ID));
        // Deliberately not in account_recipes/character_recipes: a genuine missing discoverable recipe.
        fixtures.execute("INSERT INTO items (item_id, name) VALUES (%d, 'Discovery Widget')".formatted(WIDGET_ITEM_ID));
        fixtures.execute("INSERT INTO items (item_id, name) VALUES (%d, 'Discovery Ore')".formatted(ORE_ITEM_ID));
        fixtures.execute("INSERT INTO tp_prices (item_id, sell_unit_price, buy_unit_price) VALUES (%d, 500, 400)"
                .formatted(WIDGET_ITEM_ID));
        // Alice owns exactly the bound Ore this recipe needs; Bea owns none - a relevant
        // bound-material difference the Character selector must reach (AC1/AC4), independent of
        // the Discipline+Char scope (only Alice has a Chef entry, so that scope never changes).
        fixtures.execute("INSERT INTO account_bank (slot, item_id, count, binding, bound_to) VALUES (0, %d, 2, 'Character', 'Alice')"
                .formatted(ORE_ITEM_ID));
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) throws Exception {
        var app = (javafx.application.Application) Class.forName("Gw2App").getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void manualRefreshPreservesCharacterDisciplineSortAndSearchSelections() throws Exception {
        clickOn("Crafting Discover Helper");

        JavaFxUiSupport.waitUntil("all three ComboBoxes to be constructed", Duration.ofSeconds(10),
                () -> lookup(".combo-box").queryAllAs(ComboBox.class).size() >= 3);

        @SuppressWarnings("unchecked")
        ComboBox<String> characterBox = fx(() -> (ComboBox<String>) lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> "Character".equals(box.getPromptText()))
                .findFirst().orElseThrow());

        @SuppressWarnings("unchecked")
        ComboBox<DiscChoice> disciplineBox = fx(() -> (ComboBox<DiscChoice>) lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box != characterBox)
                .filter(box -> box.getItems().isEmpty() || box.getItems().getFirst() instanceof DiscChoice)
                .findFirst().orElseThrow());

        @SuppressWarnings("unchecked")
        ComboBox<String> sortBox = fx(() -> (ComboBox<String>) lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box != characterBox && box != disciplineBox)
                .findFirst().orElseThrow());

        JavaFxUiSupport.waitUntil("character and discipline selectors populated", Duration.ofSeconds(10),
                () -> !characterBox.getItems().isEmpty() && !disciplineBox.getItems().isEmpty());

        interact(() -> {
            characterBox.getSelectionModel().select("Alice");
            disciplineBox.getSelectionModel().selectFirst();
        });

        // Discovery's "buy missing mats" defaults on; with buying enabled the resolver also
        // evaluates buying *further* (beyond-owned) units of the bound, unpriced Ore and reports
        // the whole row as PRICE_UNAVAILABLE regardless of Alice's already-sufficient ownership
        // (DOMAIN_SPEC.md section 21 - a missing purchase price is never treated as free/valid).
        // Disabling it isolates this test's own concern (selection/refresh preservation and
        // owned-bound-material recalculation), matching CraftingProfitCoordinatedScopeIT's own
        // buying-disabled comparison; the buying+no-price interaction itself is
        // STORY-DOM-013/CraftingDiscoveryViewBlockedRowIT's already-covered scope.
        CheckBox allowBuyCheck = lookup("buy missing mats").queryAs(CheckBox.class);
        clickOn(allowBuyCheck);
        assertFalse(fx(allowBuyCheck::isSelected));

        TableView<?> table = lookup(".table-view").queryAs(TableView.class);
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));
        JavaFxUiSupport.waitUntil("Alice's owned bound Ore to satisfy the recipe with nothing missing",
                Duration.ofSeconds(10),
                () -> !String.valueOf(fx(() -> JavaFxUiSupport.columnValues(table, "Status / requirements")).get(0))
                        .contains("Ore"));

        interact(() -> sortBox.setValue("Buy cost (low first)"));

        TextField searchField = fx(() -> lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow());
        clickOn(searchField);
        write("widget");

        Button refreshButton = lookup("Refresh").queryButton();
        clickOn(refreshButton);

        assertEquals("Alice", fx(characterBox::getValue), "Refresh must not reset the Character selection");
        assertEquals("Buy cost (low first)", fx(sortBox::getValue), "Refresh must not reset the sort mode");
        assertEquals("widget", fx(searchField::getText), "Refresh must not clear the search filter");
        assertFalse(fx(allowBuyCheck::isSelected), "Refresh must not reset the \"buy missing mats\" checkbox");
        JavaFxUiSupport.waitForRowCount(this, table, 1, Duration.ofSeconds(10));
        assertFalse(String.valueOf(fx(() -> JavaFxUiSupport.columnValues(table, "Status / requirements")).get(0))
                        .contains("Ore"),
                "Alice's recalculation after Refresh must still show her owned bound Ore as sufficient");

        // A Character change made after Refresh has already been used must still reach the
        // calculation: Bea owns none of the bound Ore Alice owns.
        interact(() -> characterBox.getSelectionModel().select("Bea"));
        JavaFxUiSupport.waitUntil("Bea's own recalculation to report her missing bound Ore as blocked",
                Duration.ofSeconds(10),
                () -> String.valueOf(fx(() -> JavaFxUiSupport.columnValues(table, "Status / requirements")).get(0))
                        .contains("BUYING_DISABLED"));
    }

    private <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(action));
    }
}
