import application.AccountRefreshService;
import application.GlobalDataRefreshService;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import sync.AccountRefreshGateway;
import sync.GlobalDataRefreshGateway;
import uiverify.JavaFxUiSupport;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-005: deterministic real-window regression proving {@code Gw2App}'s "Sync ALL
 * tradeable Items, Recipes and (re)build Crafting Graph" button delegates to
 * {@code application.GlobalDataRefreshService} - instead of calling {@code sync.*}/{@code repo.*}
 * directly - while still running on a background thread and reporting success/failure back on
 * the JavaFX thread, using a controlled fake service instead of the live GW2 API/database.
 *
 * <p>Lives in the default package (like {@code Gw2AppSyncAccountIT}) so it can call
 * {@code Gw2App.start(Stage, AccountRefreshService, GlobalDataRefreshService)} directly instead
 * of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - this
 * needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=Gw2AppSyncGlobalDataIT}
 */
class Gw2AppSyncGlobalDataIT extends ApplicationTest {

    private final FakeAccountRefreshService fakeAccountRefreshService = new FakeAccountRefreshService();
    private final FakeGlobalDataRefreshService fakeGlobalDataRefreshService = new FakeGlobalDataRefreshService();

    @Override
    public void start(Stage stage) {
        new Gw2App().start(stage, fakeAccountRefreshService, fakeGlobalDataRefreshService);
        stage.show();
    }

    @Test
    void syncGlobalDataButton_delegatesToInjectedServiceAndReportsSuccessOffAndOnFxThread() {
        clickOn("Sync ALL tradeable Items, Recipes and (re)build Crafting Graph");

        Label status = JavaFxUiSupport.find(this, "#homeStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("status label to report a completed refresh", Duration.ofSeconds(5),
                () -> status.getText().startsWith("✅"));

        assertEquals(1, fakeGlobalDataRefreshService.callCount, "clicking the button must invoke the service exactly once");
        assertTrue(fakeGlobalDataRefreshService.ranOffFxThread, "refreshAll() must run off the JavaFX Application Thread");
    }

    private static class FakeAccountRefreshService extends AccountRefreshService {
        FakeAccountRefreshService() {
            super(new AccountRefreshGateway());
        }

        @Override
        public void refreshAll() {
            // not exercised by this button; stays inert
        }
    }

    private static class FakeGlobalDataRefreshService extends GlobalDataRefreshService {
        volatile int callCount = 0;
        volatile boolean ranOffFxThread = false;

        FakeGlobalDataRefreshService() {
            super(new GlobalDataRefreshGateway(), null);
        }

        @Override
        public void refreshAll() {
            callCount++;
            ranOffFxThread = !javafx.application.Platform.isFxApplicationThread();
        }
    }
}
