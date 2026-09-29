import application.EctoSalvageService;
import ecto.EctoSalvageCalculator;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import uiverify.JavaFxUiSupport;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-003: deterministic real-view regression proving {@code EctoView} actually renders
 * {@code application.EctoSalvageService}'s scenario results and delegates its price/profit
 * calculation to that service, using controlled results instead of the live GW2 API. Complements
 * {@code uiverify.EctoFeeNoticeSmokeIT} (real Gw2App button-click path, fee-notice wording only)
 * and {@code ecto.EctoSalvageCalculatorTest} (pure calculation): this test closes the
 * price/scenario-table verification gap {@code EctoFeeNoticeSmokeIT}'s own Javadoc records as a
 * limitation, by injecting a fake service directly instead of adding a live-API dependency.
 *
 * <p>Lives in the default package (like {@code EctoView} itself) so it can call
 * {@code EctoView.show(Stage, Runnable, EctoSalvageService)} directly instead of through
 * reflection.
 *
 * <p>Named with an "IT" suffix (see {@code CraftingProfitViewSmokeIT}) so Surefire's default
 * {@code test} goal never selects it - this needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=EctoSalvageViewIT}
 */
class EctoSalvageViewIT extends ApplicationTest {

    private static final EctoSalvageService.EctoScenarios SCENARIOS = new EctoSalvageService.EctoScenarios(
            EctoSalvageCalculator.evaluate(1000, 200),
            EctoSalvageCalculator.evaluate(1000, 240),
            EctoSalvageCalculator.evaluate(900, 200),
            EctoSalvageCalculator.evaluate(900, 240));

    private final FakeEctoSalvageService fakeService = new FakeEctoSalvageService();

    @Override
    public void start(Stage stage) {
        EctoView.show(stage, () -> {}, fakeService);
        stage.show();
    }

    @Test
    void ectoView_rendersControlledServiceResultsAndDelegatesCalculationToService() {
        awaitCompletedFetch();

        GridPane profitGrid = JavaFxUiSupport.find(this, "#ectoProfitGrid", GridPane.class);
        GridPane luckGrid = JavaFxUiSupport.find(this, "#ectoLuckGrid", GridPane.class);

        // Hand-written amounts, not recomputed from the calculator: 200c × 0.75 = 150c expected
        // gross recovered Dust, less 15% (23c) = 127c, so instant-buying at 1000c loses 873c per
        // ecto and 1000 Luck (50 ectos) costs 43 650c. At 240c the Dust recovers 180c - 27c = 153c.
        assertEquals("-0g 8s 73c", cellText(profitGrid, 1, 1));
        assertEquals("-0g 8s 47c", cellText(profitGrid, 2, 1));
        assertEquals("-0g 7s 73c", cellText(profitGrid, 1, 2));
        assertEquals("-0g 7s 47c", cellText(profitGrid, 2, 2));

        assertEquals("4g 36s 50c", cellText(luckGrid, 1, 1));
        assertEquals("4g 23s 50c", cellText(luckGrid, 2, 1));
        assertEquals("3g 86s 50c", cellText(luckGrid, 1, 2));
        assertEquals("3g 73s 50c", cellText(luckGrid, 2, 2));

        // EctoView must delegate the load to the injected service - not perform its own HTTP call.
        assertEquals(1, fakeService.callCount);
    }

    /**
     * STORY-DOM-024: the price card shows gross values only, and the two profitability tables say
     * the fee is already in them. A quote labelled "net of TP fee" - the superseded per-unit modeled
     * sale - must not be back, and no fee may be taken off a displayed price.
     */
    @Test
    void ectoView_labelsEveryDisplayedPriceGrossAndNamesTheFeeOnTheProfitabilityTables() {
        awaitCompletedFetch();

        // The Dust quotes reach the screen untouched by the fee.
        assertEquals("0g 2s 0c", labelText("#ectoDustInstantSellGross"));
        assertEquals("0g 2s 40c", labelText("#ectoDustListingSellGross"));

        // The recovered-Dust value is the expected yield at that same gross quote, before any fee:
        // 200 × 0.75 = 150c and 240 × 0.75 = 180c, not the 127c/153c the profit figures use.
        assertEquals("0g 1s 50c", labelText("#ectoDustInstantSellRecoveredGross"));
        assertEquals("0g 1s 80c", labelText("#ectoDustListingSellRecoveredGross"));

        assertTrue(labelText("#ectoProfitTitle").contains("after 15% TP fees"),
                   "the profit table must state the fee is already deducted: " + labelText("#ectoProfitTitle"));
        assertTrue(labelText("#ectoLuckTitle").contains("after 15% TP fees"),
                   "the Luck cost table likewise: " + labelText("#ectoLuckTitle"));

        String notice = JavaFxUiSupport.find(this, ".ecto-fee-notice", Label.class).getText();
        assertTrue(notice.contains("already deduct") && notice.contains("15%"),
                   "the notice must state the deducted fee: " + notice);
        assertTrue(notice.contains("gross"), "the notice must say the displayed prices are gross: " + notice);
        assertFalse(notice.contains("net of TP fee") || notice.contains("\"Net\""),
                    "no displayed price is a net-of-fee price any more: " + notice);
    }

    private void awaitCompletedFetch() {
        Label status = JavaFxUiSupport.find(this, "#ectoStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("status label to report a completed fetch", Duration.ofSeconds(5),
                () -> status.getText().startsWith("Prices fetched"));
    }

    private String labelText(String selector) {
        return JavaFxUiSupport.find(this, selector, Label.class).getText();
    }

    private static String cellText(GridPane grid, int col, int row) {
        for (Node node : grid.getChildren()) {
            Integer c = GridPane.getColumnIndex(node);
            Integer r = GridPane.getRowIndex(node);
            int cc = (c == null) ? 0 : c;
            int rr = (r == null) ? 0 : r;
            if (cc == col && rr == row && node instanceof StackPane sp
                    && !sp.getChildren().isEmpty() && sp.getChildren().get(0) instanceof Label l) {
                return l.getText();
            }
        }
        return null;
    }

    private static class FakeEctoSalvageService extends EctoSalvageService {
        int callCount = 0;

        @Override
        public EctoScenarios calculate() {
            callCount++;
            return SCENARIOS;
        }
    }
}
