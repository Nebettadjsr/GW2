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
 * STORY-APP-006 real-view regression for {@code CraftingDiscoveryView}'s "Refresh Trade Post
 * Prices" button: proves the button delegates to the injected
 * {@code application.TradingPostPriceRefreshService} instead of calling {@code sync.TpSync}
 * directly, shows the existing success/failure status text, and preserves unrelated view state
 * (the search filter) across a refresh. Reuses the disposable-schema fixture from
 * {@link CraftingUiTestFixtures} (STORY-UI-001) and the fake-service-injection pattern
 * established by {@code EctoSalvageViewIT} (STORY-APP-003).
 *
 * <p>Lives in the default package (like {@code CraftingDiscoveryView} itself) so it can call
 * {@code CraftingDiscoveryView.show(Stage, Runnable, TradingPostPriceRefreshService)} directly
 * instead of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - needs a
 * real desktop/window session and Postgres. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryViewTpRefreshIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingDiscoveryViewTpRefreshIT extends ApplicationTest {

    private CraftingUiTestFixtures fixtures;
    private FakeTradingPostPriceRefreshService fakeService;

    @BeforeAll
    void seedFixtureSchema() throws Exception {
        fixtures = new CraftingUiTestFixtures();
        // A CHAR_DISCIPLINE entry is required so the discipline selector is non-empty and the
        // button's post-refresh reloadTable.run() does not overwrite the success/failure status
        // with the "pick a scope" prompt (CraftingDiscoveryView only lists CHAR_DISCIPLINE
        // entries, unlike CraftingProfitView's unconditional "All"/base-discipline entries).
        fixtures.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Alice')");
        fixtures.execute("INSERT INTO character_crafting (character_id, discipline, rating, is_active) VALUES (1, 'Chef', 400, true)");
    }

    @AfterAll
    void dropFixtureSchema() throws Exception {
        if (fixtures != null) fixtures.dropSchemaAndClose();
    }

    @Override
    public void start(Stage stage) {
        fakeService = new FakeTradingPostPriceRefreshService();
        CraftingDiscoveryView.show(stage, () -> {}, fakeService);
        stage.show();
    }

    @Test
    void refreshTpButton_delegatesToServiceAndPreservesSearchFilter() {
        selectFirstDisciplineChoice();

        TextField searchField = lookup(".text-field").queryAllAs(TextField.class).stream()
                .filter(tf -> "Search item...".equals(tf.getPromptText()))
                .findFirst().orElseThrow();
        clickOn(searchField);
        write("widget");

        Label status = JavaFxUiSupport.find(this, "#craftingDiscoveryStatusLabel", Label.class);

        clickOn("Refresh Trade Post Prices");

        // The view's own success handler sets "✅ TP refreshed." then immediately calls
        // reloadTable.run(), which overwrites the status text again within the same JavaFX pulse
        // (pre-existing behavior, unchanged by this story) - so that success text is not
        // reliably observable by an external poll. Waiting for the subsequent reload's own
        // stable completion text still proves the refresh-then-reload sequence ran end to end.
        JavaFxUiSupport.waitUntil("TP-refresh-triggered reload to complete", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("✅ Loaded"));

        assertEquals(1, fakeService.discoveryCallCount);
        assertEquals("widget", searchField.getText(), "TP refresh must not clear the search filter");
    }

    @Test
    void refreshTpButton_showsFailureStatusOnServiceFailure() {
        selectFirstDisciplineChoice();
        fakeService.failure = new RuntimeException("simulated TP refresh failure");

        Label status = JavaFxUiSupport.find(this, "#craftingDiscoveryStatusLabel", Label.class);

        clickOn("Refresh Trade Post Prices");

        JavaFxUiSupport.waitUntil("TP refresh failure status", Duration.ofSeconds(10),
                () -> status.getText() != null && status.getText().startsWith("❌ TP refresh failed"));
    }

    @SuppressWarnings("unchecked")
    private void selectFirstDisciplineChoice() {
        JavaFxUiSupport.waitUntil("populated discipline selector", Duration.ofSeconds(10),
                () -> lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                        .anyMatch(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice));

        ComboBox<DiscChoice> disciplineBox = lookup(".combo-box").queryAllAs(ComboBox.class).stream()
                .filter(box -> !box.getItems().isEmpty() && box.getItems().get(0) instanceof DiscChoice)
                .findFirst().orElseThrow();

        interact(() -> disciplineBox.getSelectionModel().selectFirst());
    }

    private static class FakeTradingPostPriceRefreshService extends TradingPostPriceRefreshService {
        int discoveryCallCount = 0;
        RuntimeException failure;

        @Override
        public void refreshForDiscovery() throws Exception {
            if (failure != null) throw failure;
            discoveryCallCount++;
        }
    }
}
