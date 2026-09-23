import application.AccountRefreshService;
import application.TradingPostPriceRefreshService;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import repo.DiscChoice;
import uiverify.CraftingUiTestFixtures;
import uiverify.JavaFxUiSupport;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * STORY-APP-008 real-view regression for {@code CraftingDiscoveryView}'s periodic auto-refresh:
 * proves the timer's refresh body delegates to the injected
 * {@code application.AccountRefreshService} (full bank/materials/recipes/characters variant)
 * instead of calling {@code sync.AccountSync}/{@code sync.CharacterSync} directly, reloads the
 * table and updates the "Last refresh" label on success, shows the existing failure status text on
 * failure, and preserves the selected scope/search filter/sort across a refresh.
 *
 * <p>The refresh body is triggered directly via {@code CraftingDiscoveryView.autoRefreshTask}
 * rather than by waiting out the real 120-second countdown - the published {@code Runnable}
 * <em>is</em> the production task the scheduler runs, so this exercises the real path
 * deterministically. It is invoked from a plain background thread, matching the scheduler thread it
 * normally runs on.
 *
 * <p>Lives in the default package (like {@code CraftingDiscoveryView} itself) so it can call
 * {@code CraftingDiscoveryView.show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService)}
 * and read the package-private task field directly instead of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryViewAutoRefreshIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingDiscoveryViewAutoRefreshIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;
    private FakeAccountRefreshService fakeService;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();
        // A CHAR_DISCIPLINE entry is required so the discipline selector is non-empty and the
        // post-refresh reloadTable.run() does not short-circuit on the "pick a scope" prompt
        // (CraftingDiscoveryView only lists CHAR_DISCIPLINE entries).
        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Alice')");
        fixtures.execute("INSERT INTO character_crafting (character_id, discipline, rating, is_active) VALUES (1, 'Chef', 400, true)");
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) {
        fakeService = new FakeAccountRefreshService();
        CraftingDiscoveryView.show(stage, () -> {}, new TradingPostPriceRefreshService(), fakeService);
        stage.show();
    }

    @Test
    void autoRefresh_delegatesToServiceAndPreservesScopeFilterAndSort() throws Exception {
        selectFirstDisciplineChoice();

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        DiscChoice scopeBefore = disciplineBox.getValue();

        TextField searchField = searchField();
        clickOn(searchField);
        write("widget");

        ComboBox<String> sortBox = sortBox();
        interact(() -> sortBox.getSelectionModel().select("Buy cost (low first)"));

        Label status = JavaFxUiSupport.find(this, "#craftingDiscoveryStatusLabel", Label.class);

        runAutoRefreshTaskOffTheFxThread();

        JavaFxUiSupport.waitUntil("auto-refresh to delegate to the injected service exactly once",
                Duration.ofSeconds(10), () -> fakeService.refreshAllCallCount == 1);
        // The success handler sets its own status text and then calls reloadTable.run(); waiting
        // for the reload's stable completion text proves the refresh-then-reload sequence ran end
        // to end (the intermediate refresh text is overwritten too quickly to poll reliably -
        // pre-existing behavior, unchanged by this story).
        JavaFxUiSupport.waitUntil("auto-refresh-triggered reload to complete", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("✅ Loaded"));

        assertFalse(fakeService.calledOnFxThread, "auto-refresh must not run on the JavaFX thread");
        assertEquals(0, fakeService.materialsAndRecipesCallCount,
                "discovery auto-refresh must use the full account sequence, not the narrow one");
        assertEquals("Last refresh: just now", lastRefreshLabelText());
        assertEquals(scopeBefore, disciplineBox.getValue(), "auto-refresh must not change the scope");
        assertEquals("widget", searchField.getText(), "auto-refresh must not clear the search filter");
        assertEquals("Buy cost (low first)", sortBox.getValue(), "auto-refresh must not reset the sort");
    }

    @Test
    void autoRefresh_showsFailureStatusOnServiceFailure() throws Exception {
        selectFirstDisciplineChoice();
        fakeService.failure = new RuntimeException("simulated account refresh failure");

        Label status = JavaFxUiSupport.find(this, "#craftingDiscoveryStatusLabel", Label.class);

        runAutoRefreshTaskOffTheFxThread();

        JavaFxUiSupport.waitUntil("auto-refresh failure status", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("⚠️ Auto-refresh failed"));

        assertEquals("Last refresh: FAILED", lastRefreshLabelText());
    }

    private void runAutoRefreshTaskOffTheFxThread() throws Exception {
        Runnable task = CraftingDiscoveryView.autoRefreshTask;
        Thread t = new Thread(task, "auto-refresh-test");
        t.setDaemon(true);
        t.start();
        t.join();
    }

    private String lastRefreshLabelText() {
        return lookup(".label").queryAllAs(Label.class).stream()
                .map(Label::getText)
                .filter(text -> text != null && text.startsWith("Last refresh: "))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<DiscChoice> disciplineBox() {
        return lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> sortBox() {
        return lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> box.getItems().contains("Buy cost (low first)"))
                .findFirst().orElseThrow();
    }

    private TextField searchField() {
        return lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow();
    }

    private void selectFirstDisciplineChoice() {
        JavaFxUiSupport.waitUntil("populated discipline selector", Duration.ofSeconds(10),
                () -> !lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .filter(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice)
                        .toList().isEmpty());

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        interact(() -> disciplineBox.getSelectionModel().selectFirst());
    }

    private static class FakeAccountRefreshService extends AccountRefreshService {
        volatile int refreshAllCallCount = 0;
        volatile int materialsAndRecipesCallCount = 0;
        volatile boolean calledOnFxThread = false;
        volatile RuntimeException failure;

        @Override
        public void refreshAll() {
            calledOnFxThread |= Platform.isFxApplicationThread();
            if (failure != null) throw failure;
            refreshAllCallCount++;
        }

        @Override
        public void refreshMaterialsAndRecipes() {
            materialsAndRecipesCallCount++;
        }
    }
}
