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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-010 real-view regression for {@code CraftingDiscoveryView}'s two selectors: proves both
 * are populated through the injected {@code application.CharacterSelectionService} instead of a
 * view-constructed {@code repo.CharacterRepository}, and that the established behavior is
 * unchanged - Discipline+Character offers <em>only</em> per-character entries (no "All", no
 * discipline-only entry) and selects the first, the separate Character selector lists the synced
 * names and selects the first, an empty crafting read leaves the scope selector unselected with the
 * existing "pick an entry" status, a failed crafting read shows the existing
 * "Failed loading characters" status, a failed name read stays silent as before, and a Character
 * change still drives a reload.
 *
 * <p>Lives in the default package (like {@code CraftingDiscoveryView} itself) so it can call the
 * {@code show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService,
 * CharacterSelectionService)} overload directly. The view is shown per test (as in
 * {@code BankViewIT}) so each test can inject its own controlled selector results.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryViewSelectorIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingDiscoveryViewSelectorIT extends ApplicationTest {

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
    void bothSelectorsOfferOnlyPerCharacterEntriesAndTheSyncedNames() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(
                List.of(new CharacterRepository.DiscRow("Alice", "Chef", 400, true),
                        new CharacterRepository.DiscRow("Bea", "Tailor", 275, false)),
                List.of("Alice", "Bea"));

        showView(service);

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        ComboBox<String> characterBox = characterBox();
        JavaFxUiSupport.waitUntil("both selectors to be populated", Duration.ofSeconds(10),
                () -> !disciplineBox.getItems().isEmpty() && !characterBox.getItems().isEmpty());

        assertEquals(List.of("Chef lvl 400 — Alice", "Tailor lvl 275 — Bea"),
                disciplineBox.getItems().stream().map(Object::toString).toList());
        assertTrue(disciplineBox.getItems().stream().allMatch(c -> c.kind == DiscChoice.Kind.CHAR_DISCIPLINE),
                "Discovery must keep its individual-character-only scope semantics");
        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, disciplineBox.getValue().kind);
        assertEquals("Alice", disciplineBox.getValue().charName, "the first entry must stay selected");

        assertEquals(List.of("Alice", "Bea"), characterBox.getItems());
        assertEquals("Alice", characterBox.getValue(), "the first character must stay selected");

        assertEquals(1, service.craftingCallCount, "exactly one crafting-options read");
        assertEquals(1, service.namesCallCount, "exactly one character-name read");
    }

    @Test
    void noSyncedCraftingRowsLeavesTheScopeSelectorUnselectedWithTheExistingPickEntryStatus() {
        FakeCharacterSelectionService service =
                new FakeCharacterSelectionService(List.of(), List.of("Alice"));

        showView(service);

        Label status = status();
        JavaFxUiSupport.waitUntil("the existing \"pick an entry\" status", Duration.ofSeconds(20),
                () -> "Pick a 'Discipline lvl — Character' entry.".equals(status.getText()));

        assertTrue(disciplineBox().getItems().isEmpty());
        assertNull(disciplineBox().getValue(), "nothing may be auto-selected when there is nothing to select");
    }

    @Test
    void craftingOptionsReadFailureShowsTheExistingFailedLoadingCharactersStatus() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(
                List.of(), List.of());
        service.craftingFailure = new SQLException("simulated crafting options read failure");

        showView(service);

        Label status = status();
        JavaFxUiSupport.waitUntil("the existing selector failure status", Duration.ofSeconds(20),
                () -> status.getText() != null && status.getText().startsWith("❌ Failed loading characters"));

        assertTrue(status.getText().contains("simulated crafting options read failure"),
                "the repository failure message must still reach the user; got: " + status.getText());
        assertTrue(disciplineBox().getItems().isEmpty());
    }

    @Test
    void characterNameReadFailureIsNotSurfacedAndLeavesTheRestOfThePageWorking() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(
                List.of(new CharacterRepository.DiscRow("Alice", "Chef", 400, true)), List.of());
        service.namesFailure = new SQLException("simulated character name read failure");

        showView(service);

        Label status = status();
        JavaFxUiSupport.waitUntil("the scope selection to complete a reload", Duration.ofSeconds(20),
                () -> status.getText() != null && status.getText().startsWith("✅ Loaded"));

        assertTrue(characterBox().getItems().isEmpty(),
                "a failed name read must leave the character selector empty, as before");
        assertFalse(status.getText().contains("❌"),
                "Discovery's name-load failure was never surfaced in the status label; got: " + status.getText());
    }

    @Test
    void changingTheCharacterSelectionStillTriggersAReload() {
        FakeCharacterSelectionService service = new FakeCharacterSelectionService(
                List.of(new CharacterRepository.DiscRow("Alice", "Chef", 400, true)),
                List.of("Alice", "Bea"));

        showView(service);

        ComboBox<String> characterBox = characterBox();
        Label status = status();
        JavaFxUiSupport.waitUntil("the initial load to complete", Duration.ofSeconds(20),
                () -> status.getText() != null && status.getText().startsWith("✅ Loaded"));

        // The character listener runs reloadTable synchronously on the JavaFX thread, which sets
        // this status text before handing off to its background thread. Reading the label inside
        // the same FX runnable therefore proves *this* selection started a reload, with no chance
        // of an unrelated background completion being mistaken for it.
        String[] statusRightAfterSelection = new String[1];
        interact(() -> {
            characterBox.getSelectionModel().select("Bea");
            statusRightAfterSelection[0] = status.getText();
        });

        assertEquals("Loading missing discoverable recipes...", statusRightAfterSelection[0],
                "the character change must start a reload");
        JavaFxUiSupport.waitUntil("the triggered reload to complete", Duration.ofSeconds(20),
                () -> status.getText() != null && status.getText().startsWith("✅ Loaded"));

        assertEquals("Bea", characterBox.getValue());
    }

    private void showView(CharacterSelectionService selectionService) {
        interact(() -> CraftingDiscoveryView.show(stage, () -> {},
                new TradingPostPriceRefreshService(), new NoOpAccountRefreshService(), selectionService));
    }

    private Label status() {
        return JavaFxUiSupport.find(this, "#craftingDiscoveryStatusLabel", Label.class);
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> characterBox() {
        return lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> "Character".equals(box.getPromptText()))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<DiscChoice> disciplineBox() {
        ComboBox<String> characterBox = characterBox();
        return lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box != characterBox)
                .filter(box -> box.getItems().isEmpty() || box.getItems().get(0) instanceof DiscChoice)
                .findFirst().orElseThrow();
    }

    private static class FakeCharacterSelectionService extends CharacterSelectionService {
        private final List<CharacterRepository.DiscRow> rows;
        private final List<String> names;
        SQLException craftingFailure;
        SQLException namesFailure;
        volatile int craftingCallCount = 0;
        volatile int namesCallCount = 0;

        FakeCharacterSelectionService(List<CharacterRepository.DiscRow> rows, List<String> names) {
            this.rows = rows;
            this.names = names;
        }

        @Override
        public List<CharacterRepository.DiscRow> getCraftingCharacterOptions() throws SQLException {
            craftingCallCount++;
            if (craftingFailure != null) throw craftingFailure;
            return rows;
        }

        @Override
        public List<String> getCharacterNames() throws SQLException {
            namesCallCount++;
            if (namesFailure != null) throw namesFailure;
            return names;
        }
    }

    private static class NoOpAccountRefreshService extends AccountRefreshService {
        @Override public void refreshMaterialsAndRecipes() { }
        @Override public void refreshAll() { }
    }
}
