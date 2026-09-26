import application.BankContentsService;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import repo.BankRepository;
import uiverify.JavaFxUiSupport;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-009: deterministic real-window regression proving {@code BankView} renders
 * {@code application.BankContentsService}'s rows and keeps its established presentation - slot
 * ordering into the 10-column grid, stack counts, blank tiles for empty slots, and the red
 * "DB error: ..." label when the load fails - using a controlled service instead of a live
 * database.
 *
 * <p>Lives in the default package (like {@code BankView} itself) so it can call
 * {@code BankView.show(Stage, Runnable, BankContentsService)} directly.
 *
 * <p>Named with an "IT" suffix (see {@code EctoSalvageViewIT}) so Surefire's default {@code test}
 * goal never selects it - this needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=BankViewIT}
 */
class BankViewIT extends ApplicationTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void bankView_rendersControlledServiceRowsInSlotOrder() {
        List<BankRepository.BankSlotRow> rows = List.of(
                new BankRepository.BankSlotRow(0, 19721, 5, "C:\\icons\\19721.png", null, "Rare"),
                new BankRepository.BankSlotRow(1, null, null, null, null, null),
                new BankRepository.BankSlotRow(2, 24277, 250, "C:\\icons\\24277.png", null, "Basic"));
        FakeBankContentsService service = new FakeBankContentsService(rows, null);

        interact(() -> BankView.show(stage, () -> {}, service));

        GridPane grid = JavaFxUiSupport.find(this, "#bankGrid", GridPane.class);

        // One tile per slot 0..maxSlot, laid out 10 per row.
        assertEquals(3, grid.getChildren().size());
        assertEquals("5", countText(tileAt(grid, 0, 0)));
        assertTrue(((StackPane) tileAt(grid, 1, 0)).getChildren().isEmpty(), "empty slot must render a blank tile");
        assertEquals("250", countText(tileAt(grid, 2, 0)));

        assertEquals(1, service.callCount, "BankView must load through the injected service exactly once");
    }

    @Test
    void bankView_rendersTheExistingDbErrorLabelWhenTheServiceFails() {
        FakeBankContentsService service =
                new FakeBankContentsService(List.of(), new SQLException("simulated bank read failure"));

        interact(() -> BankView.show(stage, () -> {}, service));

        Label error = JavaFxUiSupport.find(this, "#bankErrorLabel", Label.class);
        assertEquals("DB error: simulated bank read failure", error.getText());
    }

    private static Node tileAt(GridPane grid, int col, int row) {
        for (Node node : grid.getChildren()) {
            Integer c = GridPane.getColumnIndex(node);
            Integer r = GridPane.getRowIndex(node);
            if ((c == null ? 0 : c) == col && (r == null ? 0 : r) == row) return node;
        }
        throw new AssertionError("No tile at column " + col + ", row " + row);
    }

    private static String countText(Node tile) {
        for (Node child : ((StackPane) tile).getChildren()) {
            if (child instanceof Label l) return l.getText();
        }
        return null;
    }

    private static class FakeBankContentsService extends BankContentsService {
        private final List<BankRepository.BankSlotRow> rows;
        private final SQLException failure;
        int callCount = 0;

        FakeBankContentsService(List<BankRepository.BankSlotRow> rows, SQLException failure) {
            this.rows = rows;
            this.failure = failure;
        }

        @Override
        public List<BankRepository.BankSlotRow> getBankContents() throws SQLException {
            callCount++;
            if (failure != null) throw failure;
            return rows;
        }
    }
}
