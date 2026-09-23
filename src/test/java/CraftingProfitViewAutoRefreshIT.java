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
 * STORY-APP-008 real-view regression for {@code CraftingProfitView}'s periodic auto-refresh: proves
 * the timer's refresh body delegates to the injected {@code application.AccountRefreshService}
 * (narrow materials+recipes variant) instead of calling {@code sync.AccountSync} directly, reloads
 * the table on success, shows the existing failure status text on failure, and preserves the
 * selected scope/search filter/sort across a refresh.
 *
 * <p>The refresh body is triggered directly via {@code CraftingProfitView.autoRefreshTask} rather
 * than by waiting out the real 90-second countdown - the published {@code Runnable} <em>is</em> the
 * production task the scheduler runs, so this exercises the real path deterministically. It is
 * invoked from a plain background thread, matching the scheduler thread it normally runs on.
 *
 * <p>Lives in the default package (like {@code CraftingProfitView} itself) so it can call
 * {@code CraftingProfitView.show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService)}
 * and read the package-private task field directly instead of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewAutoRefreshIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewAutoRefreshIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;
    private FakeAccountRefreshService fakeService;

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
        fakeService = new FakeAccountRefreshService();
        CraftingProfitView.show(stage, () -> {}, new TradingPostPriceRefreshService(), fakeService);
        stage.show();
    }

    @Test
    void autoRefresh_delegatesToServiceAndPreservesScopeFilterAndSort() throws Exception {
        waitForPopulatedScope();

        ComboBox<DiscChoice> disciplineBox = disciplineBox();
        DiscChoice scopeBefore = disciplineBox.getValue();

        TextField searchField = searchField();
        clickOn(searchField);
        write("widget");

        ComboBox<String> sortBox = sortBox();
        interact(() -> sortBox.getSelectionModel().select("Profit per item"));

        // The success handler sets its status text and then calls reloadTable.run(), whose own
        // completion clears the status back to "" (pre-existing behavior, unchanged by this story).
        // Marking the label first makes waiting for that clear a real check that the refresh ran
        // the reload, rather than one the already-completed initial load satisfies.
        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);
        interact(() -> status.setText("before auto-refresh"));

        runAutoRefreshTaskOffTheFxThread();

        JavaFxUiSupport.waitUntil("auto-refresh to delegate to the injected service exactly once",
                Duration.ofSeconds(10), () -> fakeService.materialsAndRecipesCallCount == 1);
        JavaFxUiSupport.waitUntil("auto-refresh-triggered reload to complete",
                Duration.ofSeconds(10), () -> "".equals(status.getText()));

        assertFalse(fakeService.calledOnFxThread, "auto-refresh must not run on the JavaFX thread");
        assertEquals(0, fakeService.refreshAllCallCount,
                "profit auto-refresh must not sync bank/characters");
        assertEquals(scopeBefore, disciplineBox.getValue(), "auto-refresh must not change the scope");
        assertEquals("widget", searchField.getText(), "auto-refresh must not clear the search filter");
        assertEquals("Profit per item", sortBox.getValue(), "auto-refresh must not reset the sort");
    }

    @Test
    void autoRefresh_showsFailureStatusOnServiceFailure() throws Exception {
        waitForPopulatedScope();
        fakeService.failure = new RuntimeException("simulated account refresh failure");

        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);

        runAutoRefreshTaskOffTheFxThread();

        JavaFxUiSupport.waitUntil("auto-refresh failure status", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("⚠️ Auto-refresh failed"));
    }

    private void runAutoRefreshTaskOffTheFxThread() throws Exception {
        Runnable task = CraftingProfitView.autoRefreshTask;
        Thread t = new Thread(task, "auto-refresh-test");
        t.setDaemon(true);
        t.start();
        t.join();
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
                .filter(box -> box.getItems().contains("Profit per item"))
                .findFirst().orElseThrow();
    }

    private TextField searchField() {
        return lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow();
    }

    private void waitForPopulatedScope() {
        JavaFxUiSupport.waitUntil("populated discipline selector", Duration.ofSeconds(10),
                () -> !lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .filter(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice)
                        .toList().isEmpty());
    }

    private static class FakeAccountRefreshService extends AccountRefreshService {
        volatile int materialsAndRecipesCallCount = 0;
        volatile int refreshAllCallCount = 0;
        volatile boolean calledOnFxThread = false;
        volatile RuntimeException failure;

        @Override
        public void refreshMaterialsAndRecipes() {
            calledOnFxThread |= Platform.isFxApplicationThread();
            if (failure != null) throw failure;
            materialsAndRecipesCallCount++;
        }

        @Override
        public void refreshAll() {
            refreshAllCallCount++;
        }
    }
}
