import application.TradingPostPriceRefreshService;
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

/**
 * STORY-APP-006 real-view regression for {@code CraftingProfitView}'s "Refresh Trade Post
 * Prices" button: proves the button delegates to the injected
 * {@code application.TradingPostPriceRefreshService} instead of calling {@code sync.TpSync}
 * directly, shows the existing success/failure status text, and preserves unrelated view state
 * (the search filter) across a refresh. Reuses the disposable-schema fixture from
 * {@link CraftingUiTestFixtures} (STORY-UI-001) and the fake-service-injection pattern
 * established by {@code EctoSalvageViewIT} (STORY-APP-003).
 *
 * <p>Lives in the default package (like {@code CraftingProfitView} itself) so it can call
 * {@code CraftingProfitView.show(Stage, Runnable, TradingPostPriceRefreshService)} directly
 * instead of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitViewTpRefreshIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitViewTpRefreshIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;
    private FakeTradingPostPriceRefreshService fakeService;

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
        fakeService = new FakeTradingPostPriceRefreshService();
        CraftingProfitView.show(stage, () -> {}, fakeService);
        stage.show();
    }

    @Test
    void refreshTpButton_delegatesToServiceAndPreservesSearchFilter() {
        waitForPopulatedScope();

        TextField searchField = lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow();
        clickOn(searchField);
        write("widget");

        clickOn("Refresh Trade Post Prices");

        // The view's own success handler sets "✅ TP refreshed." then immediately calls
        // reloadTable.run(), which overwrites the status text again within the same JavaFX pulse
        // (pre-existing behavior, unchanged by this story) - so that success text is not
        // reliably observable by an external poll. Delegation is instead proven directly via the
        // fake service's call count.
        JavaFxUiSupport.waitUntil("TP refresh to delegate to the injected service exactly once",
                Duration.ofSeconds(10), () -> fakeService.profitCallCount == 1);

        assertEquals("widget", searchField.getText(), "TP refresh must not clear the search filter");
    }

    @Test
    void refreshTpButton_showsFailureStatusOnServiceFailure() {
        waitForPopulatedScope();
        fakeService.failure = new RuntimeException("simulated TP refresh failure");

        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class);

        clickOn("Refresh Trade Post Prices");

        JavaFxUiSupport.waitUntil("TP refresh failure status", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("❌ TP refresh failed"));
    }

    @SuppressWarnings("unchecked")
    private void waitForPopulatedScope() {
        JavaFxUiSupport.waitUntil("populated discipline selector", Duration.ofSeconds(10),
                () -> lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .anyMatch(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice));
    }

    private static class FakeTradingPostPriceRefreshService extends TradingPostPriceRefreshService {
        int profitCallCount = 0;
        RuntimeException failure;

        @Override
        public void refreshForProfit() throws Exception {
            if (failure != null) throw failure;
            profitCallCount++;
        }
    }
}
