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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-DOM-015 real-view regression for {@code DOMAIN_SPEC.md} section 2.2.1: a manual
 * "Refresh" must reload using the user's current Discipline scope, sort mode, search text and
 * "buy missing mats" checkbox instead of resetting any of them to their startup defaults, and a
 * scope change made after a Refresh must still reach the displayed calculation. Reuses
 * {@link CraftingUiTestFixtures}/{@link JavaFxUiSupport} (STORY-UI-001) and the same
 * two-character/bound-ore fixture shape as {@code CraftingProfitCoordinatedScopeIT}
 * (STORY-DOM-014).
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewRefreshPreservationIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewRefreshPreservationIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();
        fixtures.execute("INSERT INTO characters VALUES (1, 'Alice'), (2, 'Bea')");
        fixtures.execute("INSERT INTO character_crafting (character_id, discipline, rating) VALUES (1, 'Chef', 400), (2, 'Chef', 400)");
        fixtures.execute("INSERT INTO recipes VALUES (1, 100, 1, 400, ARRAY['Chef'], ARRAY[]::text[])");
        fixtures.execute("INSERT INTO account_recipes VALUES (1)");
        fixtures.execute("INSERT INTO recipe_ingredients VALUES (1, 200, 2)");
        fixtures.execute("INSERT INTO items (item_id, name) VALUES (100, 'Refresh Widget'), (200, 'Bound Ore')");
        fixtures.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) VALUES (100, 500, 600)");
        // Alice owns exactly enough bound Ore for one craft; Bea owns none - the relevant
        // bound-material difference a scope change must reach (AC1/AC4).
        fixtures.execute("INSERT INTO account_bank (slot, item_id, count, binding, bound_to) VALUES (0, 200, 2, 'Character', 'Alice')");
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
    void manualRefreshPreservesScopeSortSearchAndCheckboxSelections() throws Exception {
        clickOn("Crafting Profit Calculator");

        ComboBox<DiscChoice> scope = scopeBox();
        TableView<?> table = lookup(".table-view").queryAs(TableView.class);

        select(scope, "Alice");
        expectCraftable(table, 1);

        ComboBox<String> sortBox = fx(() -> (ComboBox<String>) lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box.getItems().contains("Max craftable count"))
                .findFirst().orElseThrow());
        interact(() -> sortBox.setValue("Max craftable count"));

        TextField searchField = fx(() -> lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow());
        clickOn(searchField);
        write("widget");

        CheckBox allowBuyCheck = lookup("buy missing mats").queryAs(CheckBox.class);
        clickOn(allowBuyCheck);
        assertTrue(fx(allowBuyCheck::isSelected));
        expectCraftable(table, 1); // Alice already owns enough; enabling buying changes nothing here.

        Button refreshButton = lookup("Refresh").queryButton();
        clickOn(refreshButton);

        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, fx(() -> scope.getValue().kind),
                "Refresh must not reset the Discipline scope selection");
        assertEquals("Alice", fx(() -> scope.getValue().charName));
        assertEquals("Max craftable count", fx(sortBox::getValue), "Refresh must not reset the sort mode");
        assertEquals("widget", fx(searchField::getText), "Refresh must not clear the search filter");
        assertTrue(fx(allowBuyCheck::isSelected), "Refresh must not reset the \"buy missing mats\" checkbox");
        expectCraftable(table, 1);

        // A scope change made after Refresh has already been used must still reach the
        // calculation: Bea owns none of the bound Ore Alice owns. Buying is turned back off first
        // so the comparison uses the same "own mats only" semantics as CraftingProfitCoordinatedScopeIT.
        clickOn(allowBuyCheck);
        select(scope, "Bea");
        expectCraftable(table, 0);
    }

    @SuppressWarnings("unchecked")
    private ComboBox<DiscChoice> scopeBox() throws Exception {
        JavaFxUiSupport.waitUntil("populated discipline selector", Duration.ofSeconds(10),
                () -> fx(() -> lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .anyMatch(box -> !box.getItems().isEmpty() && box.getItems().getFirst() instanceof DiscChoice)));
        return fx(() -> (ComboBox<DiscChoice>) lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> !box.getItems().isEmpty() && box.getItems().getFirst() instanceof DiscChoice)
                .findFirst().orElseThrow());
    }

    private void select(ComboBox<DiscChoice> box, String charName) {
        interact(() -> box.setValue(box.getItems().stream()
                .filter(choice -> choice.kind == DiscChoice.Kind.CHAR_DISCIPLINE
                        && "Chef".equals(choice.discipline) && charName.equals(choice.charName))
                .findFirst().orElseThrow()));
    }

    private void expectCraftable(TableView<?> table, int count) {
        JavaFxUiSupport.waitUntil("displayed craftable count " + count, Duration.ofSeconds(10),
                () -> fx(() -> JavaFxUiSupport.columnValues(table, "Craftable")).equals(List.of(count)));
    }

    private <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(action));
    }
}
