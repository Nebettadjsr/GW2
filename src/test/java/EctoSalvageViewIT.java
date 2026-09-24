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
        Label status = JavaFxUiSupport.find(this, "#ectoStatusLabel", Label.class);
        JavaFxUiSupport.waitUntil("status label to report a completed fetch", Duration.ofSeconds(5),
                () -> status.getText().startsWith("Prices fetched"));

        GridPane profitGrid = JavaFxUiSupport.find(this, "#ectoProfitGrid", GridPane.class);
        GridPane luckGrid = JavaFxUiSupport.find(this, "#ectoLuckGrid", GridPane.class);

        assertEquals(util.CoinUtils.formatSigned(SCENARIOS.instantBuyInstantSell().profitPerEcto()), cellText(profitGrid, 1, 1));
        assertEquals(util.CoinUtils.formatSigned(SCENARIOS.instantBuyListingSell().profitPerEcto()), cellText(profitGrid, 2, 1));
        assertEquals(util.CoinUtils.formatSigned(SCENARIOS.listingBuyInstantSell().profitPerEcto()), cellText(profitGrid, 1, 2));
        assertEquals(util.CoinUtils.formatSigned(SCENARIOS.listingBuyListingSell().profitPerEcto()), cellText(profitGrid, 2, 2));

        assertEquals(util.CoinUtils.format(SCENARIOS.instantBuyInstantSell().costPer1000Luck()), cellText(luckGrid, 1, 1));
        assertEquals(util.CoinUtils.format(SCENARIOS.instantBuyListingSell().costPer1000Luck()), cellText(luckGrid, 2, 1));
        assertEquals(util.CoinUtils.format(SCENARIOS.listingBuyInstantSell().costPer1000Luck()), cellText(luckGrid, 1, 2));
        assertEquals(util.CoinUtils.format(SCENARIOS.listingBuyListingSell().costPer1000Luck()), cellText(luckGrid, 2, 2));

        // EctoView must delegate the load to the injected service - not perform its own HTTP call.
        assertEquals(1, fakeService.callCount);
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
