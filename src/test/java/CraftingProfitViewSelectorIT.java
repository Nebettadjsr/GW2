import application.AccountRefreshService;
import application.CharacterSelectionService;
import application.TradingPostPriceRefreshService;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import repo.CharacterRepository;
import repo.DiscChoice;
import uiverify.CraftingUiTestFixtures;
import uiverify.JavaFxUiSupport;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-010 real-view regression for {@code CraftingProfitView}'s Discipline selector: proves
 * it is populated through the injected {@code application.CharacterSelectionService} instead of a
 * view-constructed {@code repo.CharacterRepository}, and that the established selector behavior is
 * unchanged - "All" first, then the fixed nine base disciplines, then one entry per synced
 * character discipline in service order, defaulting to "All"; an empty read still yields
 * All + base disciplines; a failed read stays silent (the view only printed the stack trace) while
 * the table keeps loading; and a genuine scope change still drives a reload.
 *
 * <p>Lives in the default package (like {@code CraftingProfitView} itself) so it can call the
 * {@code show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService,
 * CharacterSelectionService)} overload directly. The view is shown per test (as in
 * {@code BankViewIT}) so each test can inject its own controlled selector result.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewSelectorIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewSelectorIT extends ApplicationTest {

    private static final List<String> BASE_DISCIPLINES = List.of(
            "Chef", "Huntsman", "Weaponsmith", "Armorsmith", "Artificer",
            "Tailor", "Leatherworker", "Jeweler", "Scribe");

    private CraftingUiTestFixtures fixtures;
    private Stage stage;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void selectorOffersAllThenBaseDisciplinesThenPerCharacterEntriesAndDefaultsToAll() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true),
                new CharacterRepository.DiscRow("Bea", "Tailor", 275, false)), null);

        showView(service);

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        JavaFxUiSupport.waitUntil("discipline selector to be populated", Duration.ofSeconds(10),
                () -> !disciplineBox.getItems().isEmpty());

        List<String> labels = disciplineBox.getItems().stream().map(Object::toString).toList();
        List<String> expected = new java.util.ArrayList<>();
        expected.add("All");
        expected.addAll(BASE_DISCIPLINES);
        expected.add("Chef lvl 400 — Alice");
        expected.add("Tailor lvl 275 — Bea");
        assertEquals(expected, labels, "selector contents/order must be unchanged");

        assertEquals(DiscChoice.Kind.ALL, disciplineBox.getValue().kind, "default scope must stay All");
        assertEquals(1, service.craftingCallCount,
                "the selector must read through the application boundary exactly once");
        assertEquals(0, service.namesCallCount, "Profit must not load the character-name list");
    }

    @Test
    void noSyncedCraftingRowsStillOffersAllAndTheBaseDisciplines() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(List.of(), null);

        showView(service);

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        JavaFxUiSupport.waitUntil("discipline selector to be populated", Duration.ofSeconds(10),
                () -> !disciplineBox.getItems().isEmpty());

        List<String> labels = disciplineBox.getItems().stream().map(Object::toString).toList();
        List<String> expected = new java.util.ArrayList<>();
        expected.add("All");
        expected.addAll(BASE_DISCIPLINES);
        assertEquals(expected, labels);
        assertEquals(DiscChoice.Kind.ALL, disciplineBox.getValue().kind);
    }

    @Test
    void selectorReadFailureLeavesTheSelectorEmptyWithoutChangingTheStatusText() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(
                List.of(), new SQLException("simulated selector read failure"));

        showView(service);

        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);
        // The table load is independent of the selector load and finishes by clearing the status;
        // waiting for that gives the failed selector load every chance to surface a message first.
        JavaFxUiSupport.waitUntil("the independent table load to complete", Duration.ofSeconds(20),
                () -> "".equals(status.getText()));

        assertTrue(disciplineBox().getItems().isEmpty(),
                "a failed selector read must leave the selector empty, as before");
        assertFalse(status.getText().contains("❌"),
                "Profit's selector-load failure was never surfaced in the status label; got: " + status.getText());
        assertTrue(lookup("Crafting Profit Analyzer").tryQuery().isPresent(),
                "the page must still render when the selector read fails");
    }

    @Test
    void choosingADifferentScopeStillTriggersAReload() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true)), null);

        showView(service);

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        JavaFxUiSupport.waitUntil("discipline selector to be populated", Duration.ofSeconds(10),
                () -> !disciplineBox.getItems().isEmpty());

        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("initial table load to complete", Duration.ofSeconds(20),
                () -> "".equals(status.getText()));

        DiscChoice characterEntry = disciplineBox.getItems().stream()
                .filter(c -> c.kind == DiscChoice.Kind.CHAR_DISCIPLINE)
                .findFirst().orElseThrow();

        // The scope listener runs reloadTable synchronously on the JavaFX thread, which sets this
        // status text before handing off to its background thread. Reading the label inside the
        // same FX runnable therefore proves *this* selection started a reload, with no chance of
        // an unrelated background completion being mistaken for it.
        String[] statusRightAfterSelection = new String[1];
        interact(() -> {
            disciplineBox.getSelectionModel().select(characterEntry);
            statusRightAfterSelection[0] = status.getText();
        });

        assertTrue(statusRightAfterSelection[0].startsWith("MaxBuy UI="),
                "the scope change must start a reload; status was: " + statusRightAfterSelection[0]);
        JavaFxUiSupport.waitUntil("the triggered reload to complete", Duration.ofSeconds(20),
                () -> "".equals(status.getText()));

        assertEquals("CHARACTER|Alice|Chef", CraftingProfitView.scopeKey(disciplineBox.getValue()),
                "the chosen per-character scope must be the one the reload used");
    }

    private void showView(CharacterSelectionService selectionService) {
        interact(() -> CraftingProfitView.show(stage, () -> {},
                new TradingPostPriceRefreshService(), new NoOpAccountRefreshService(), selectionService));
    }

    @SuppressWarnings("unchecked")
    private ComboBox<DiscChoice> disciplineBox() {
        return lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box.getItems().isEmpty() || box.getItems().get(0) instanceof DiscChoice)
                .findFirst().orElseThrow();
    }

    private static class FakeCharacterSelectionService extends CharacterSelectionService {
        private final List<CharacterRepository.DiscRow> rows;
        private final SQLException failure;
        volatile int craftingCallCount = 0;
        volatile int namesCallCount = 0;

        FakeCharacterSelectionService(List<CharacterRepository.DiscRow> rows, SQLException failure) {
            this.rows = rows;
            this.failure = failure;
        }

        @Override
        public List<CharacterRepository.DiscRow> getCraftingCharacterOptions() throws SQLException {
            craftingCallCount++;
            if (failure != null) throw failure;
            return rows;
        }

        @Override
        public List<String> getCharacterNames() {
            namesCallCount++;
            return List.of();
        }
    }

    private static class NoOpAccountRefreshService extends AccountRefreshService {
        @Override public void refreshMaterialsAndRecipes() { }
        @Override public void refreshAll() { }
    }
}
