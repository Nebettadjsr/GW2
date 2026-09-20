package uiverify;

import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-DOM-016: verifies the real {@code EctoView}'s fee notice, using the reusable
 * {@link JavaFxUiSupport} harness (STORY-UI-001), against the real application (not a standalone
 * demonstration scene) - proving the replaced fee-exclusion warning actually reaches the rendered
 * UI, not just the calculation layer covered by {@code EctoSalvageCalculatorTest}.
 *
 * <p>Scoped to the fee notice only, not the price/scenario tables: unlike {@code CraftingProfitView}
 * (DB-backed, so {@code CraftingUiTestFixtures} can point it at a disposable schema), {@code
 * EctoView} fetches its Trading Post quotes directly from the live GW2 API with no injectable
 * seam. Verifying the displayed profit/Luck-cost tables deterministically would require adding a
 * new test seam to production code - out of this story's fee-model-only scope
 * (STORY-DOM-016 Constraints: "no new fee model, yield assumption, framework or broad
 * restructuring"). This is a recorded limitation, not solved by this story: the fee arithmetic
 * itself is fully covered deterministically by {@code EctoSalvageCalculatorTest} (Layer 1), and
 * {@code EctoView}'s grid-filling methods now delegate to that same calculator (confirmed by
 * direct code reading), so no separate reimplementation of the formula exists to drift out of
 * sync.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - this needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=EctoFeeNoticeSmokeIT}
 */
class EctoFeeNoticeSmokeIT extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        // Gw2App lives in the unnamed package and so cannot be imported from a named test
        // package; reflection is the only way to reference it from here.
        Class<?> appClass = Class.forName("Gw2App");
        javafx.application.Application app = (javafx.application.Application) appClass.getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void ectoView_rendersFeeInclusiveNoticeInsteadOfFeeExclusionWarning() {
        clickOn("Salvage Ecto for Dust & Luck");

        Label feeNotice = JavaFxUiSupport.find(this, ".ecto-fee-notice", Label.class, Duration.ofSeconds(5));

        String text = feeNotice.getText();
        assertTrue(text.contains("already deduct"), "expected fee-inclusive wording, got: " + text);
        assertTrue(text.contains("15%"), "expected the fee rate to be stated, got: " + text);
        assertFalse(text.contains("do not include"), "old fee-exclusion warning must be gone, got: " + text);
    }
}
