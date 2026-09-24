import application.AccountRefreshService;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import sync.AccountRefreshGateway;
import uiverify.JavaFxUiSupport;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-004: deterministic real-window regression proving {@code Gw2App}'s "Sync Account"
 * button delegates to {@code application.AccountRefreshService} - instead of calling
 * {@code sync.*} directly - while still running on a background thread and reporting
 * success/failure back on the JavaFX thread, using a controlled fake service instead of the live
 * GW2 API/database.
 *
 * <p>Lives in the default package (like {@code EctoSalvageViewIT}) so it can call
 * {@code Gw2App.start(Stage, AccountRefreshService)} directly instead of through reflection.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - this needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=Gw2AppSyncAccountIT}
 */
class Gw2AppSyncAccountIT extends ApplicationTest {

    private final FakeAccountRefreshService fakeService = new FakeAccountRefreshService();

    @Override
    public void start(Stage stage) {
        new Gw2App().start(stage, fakeService);
        stage.show();
    }

    @Test
    void syncAccountButton_delegatesToInjectedServiceAndReportsSuccessOffAndOnFxThread() {
        clickOn("Sync Account (Bank, Mats, Characters, Recipes)");

        Label status = JavaFxUiSupport.find(this, "#homeStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("status label to report a completed refresh", Duration.ofSeconds(5),
                () -> status.getText().startsWith("✅"));

        assertEquals(1, fakeService.callCount, "clicking the button must invoke the service exactly once");
        assertTrue(fakeService.ranOffFxThread, "refreshAll() must run off the JavaFX Application Thread");
    }

    private static class FakeAccountRefreshService extends AccountRefreshService {
        volatile int callCount = 0;
        volatile boolean ranOffFxThread = false;

        FakeAccountRefreshService() {
            super(new AccountRefreshGateway());
        }

        @Override
        public void refreshAll() {
            callCount++;
            ranOffFxThread = !javafx.application.Platform.isFxApplicationThread();
        }
    }
}
