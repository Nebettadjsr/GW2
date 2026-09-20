package uiverify;

import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import repo.DiscChoice;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** DOM-014 scope-to-displayed-results coverage, using the existing UI-001 capability. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitCoordinatedScopeIT extends ApplicationTest {
    private CraftingUiTestFixtures fixture;

    @BeforeAll
    void seed() throws Exception {
        fixture = new CraftingUiTestFixtures();
        fixture.execute("INSERT INTO characters VALUES (1, 'Alice'), (2, 'Bea'), (3, 'Novice'), (4, 'Other')");
        fixture.execute("INSERT INTO character_crafting (character_id, discipline, rating) VALUES (1, 'Chef', 400), (2, 'Chef', 400), (3, 'Chef', 1), (4, 'Tailor', 400)");
        fixture.execute("INSERT INTO recipes VALUES (1, 100, 1, 400, ARRAY['Chef'], ARRAY[]::text[])");
        fixture.execute("INSERT INTO account_recipes VALUES (1)");
        fixture.execute("INSERT INTO recipe_ingredients VALUES (1, 200, 2)");
        fixture.execute("INSERT INTO items (item_id, name) VALUES (100, 'Coordinated Widget'), (200, 'Bound Ore')");
        fixture.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) VALUES (100, 500, 600)");
        fixture.execute("INSERT INTO account_bank (slot, item_id, count, binding, bound_to) VALUES (0, 200, 2, 'Character', 'Alice'), (1, 200, 4, 'Character', 'Bea'), (2, 200, 8, 'Character', 'Novice'), (3, 200, 16, 'Character', 'Other')");
    }

    @AfterAll
    void cleanup() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Override
    public void start(Stage stage) throws Exception {
        var app = (javafx.application.Application) Class.forName("Gw2App").getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void scopesRespectRatingsAndOwnersAndDiscoveryStaysIndividual() throws Exception {
        clickOn("Crafting Profit Calculator");
        ComboBox<DiscChoice> scope = scopeBox();
        assertEquals(DiscChoice.Kind.ALL, fx(() -> scope.getValue().kind));
        assertEquals(0L, fx(() -> lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> "Character".equals(box.getPromptText())).count()));
        TableView<?> table = lookup(".table-view").queryAs(TableView.class);
        expectCount(table, 3); // Alice + Bea; Novice and Other cannot contribute bound ore.
        select(scope, DiscChoice.Kind.CHAR_DISCIPLINE, "Alice");
        expectCount(table, 1);
        select(scope, DiscChoice.Kind.DISCIPLINE_ONLY, null);
        expectCount(table, 3);
        select(scope, DiscChoice.Kind.CHAR_DISCIPLINE, "Bea");
        expectCount(table, 2);
        select(scope, DiscChoice.Kind.CHAR_DISCIPLINE, "Novice");
        expectCount(table, 0);

        clickOn("← Back");
        clickOn("Crafting Discover Helper");
        JavaFxUiSupport.waitUntil("Discovery individual character choices", Duration.ofSeconds(10),
                () -> fx(() -> lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .anyMatch(box -> "Character".equals(box.getPromptText()) && box.getItems().size() == 4)));
        fx(() -> {
            var characters = lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                    .filter(box -> "Character".equals(box.getPromptText())).findFirst().orElseThrow();
            assertEquals(List.of("Alice", "Bea", "Novice", "Other"), characters.getItems());
            assertEquals("Alice", characters.getValue());
            return null;
        });
        ComboBox<DiscChoice> discoveryScope = scopeBox();
        assertTrue(fx(() -> discoveryScope.getItems().stream()
                .allMatch(choice -> choice.kind == DiscChoice.Kind.CHAR_DISCIPLINE)));

        fixture.execute("DELETE FROM characters");
        clickOn("← Back");
        clickOn("Crafting Profit Calculator");
        ComboBox<DiscChoice> emptyScope = scopeBox();
        assertEquals(DiscChoice.Kind.ALL, fx(emptyScope::getValue).kind);
        expectCount(lookup(".table-view").queryAs(TableView.class), 0);
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

    private void select(ComboBox<DiscChoice> box, DiscChoice.Kind kind, String name) {
        interact(() -> box.setValue(box.getItems().stream().filter(choice -> choice.kind == kind
                && "Chef".equals(choice.discipline) && java.util.Objects.equals(name, choice.charName))
                .findFirst().orElseThrow()));
    }

    private void expectCount(TableView<?> table, int count) {
        JavaFxUiSupport.waitUntil("displayed craftable count " + count, Duration.ofSeconds(10),
                () -> fx(() -> JavaFxUiSupport.columnValues(table, "Craftable").equals(List.of(count))));
    }

    private <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(action));
    }
}
