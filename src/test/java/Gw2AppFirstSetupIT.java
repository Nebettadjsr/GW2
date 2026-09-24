import application.AccountRefreshService;
import application.GlobalDataRefreshService;
import application.InitialSetupService;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import uiverify.JavaFxUiSupport;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-007: deterministic real-window regression proving {@code Gw2App}'s "First-time DB
 * Setup" button delegates to {@code application.InitialSetupService} - instead of calling
 * {@code sync.*}/{@code repo.*}/{@code craft.*} directly from its button handler - while still
 * running on a background thread, showing the confirmation dialog first, and reporting
 * success/failure back on the JavaFX thread, using a controlled fake service instead of the live
 * GW2 API/database.
 *
 * <p>Lives in the default package (like {@code Gw2AppSyncAccountIT}) so it can call
 * {@code Gw2App.start(Stage, AccountRefreshService, GlobalDataRefreshService, InitialSetupService)}
 * directly instead of through reflection.
 *
 * <p>Named with an "IT" suffix so Surefire's default {@code test} goal never selects it - this
 * needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=Gw2AppFirstSetupIT}
 */
class Gw2AppFirstSetupIT extends ApplicationTest {

    private final FakeInitialSetupService fakeService = new FakeInitialSetupService();

    @Override
    public void start(Stage stage) {
        new Gw2App().start(stage, new AccountRefreshService(), new GlobalDataRefreshService(), fakeService);
        stage.show();
    }

    @Test
    void firstSetupButton_delegatesToInjectedServiceAndReportsSuccessOffAndOnFxThread() {
        clickOn("First-time DB Setup (Base Fill)");
        clickOn("OK");

        Label status = JavaFxUiSupport.find(this, "#homeStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("status label to report a completed setup", Duration.ofSeconds(5),
                () -> status.getText().startsWith("✅"));

        assertEquals(1, fakeService.callCount, "clicking the button must invoke the service exactly once");
        assertTrue(fakeService.ranOffFxThread, "firstFill() must run off the JavaFX Application Thread");
    }

    private static class FakeInitialSetupService extends InitialSetupService {
        volatile int callCount = 0;
        volatile boolean ranOffFxThread = false;

        @Override
        public void firstFill() {
            callCount++;
            ranOffFxThread = !javafx.application.Platform.isFxApplicationThread();
        }
    }
}
