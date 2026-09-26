import application.MaterialStorageService;
import application.MaterialStorageService.MaterialCategory;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import repo.MaterialStorageRepository.MaterialStorageRow;
import uiverify.JavaFxUiSupport;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-009: deterministic real-window regression proving {@code MaterialsView} renders
 * {@code application.MaterialStorageService}'s categories and keeps its established presentation -
 * one titled block per category in the service's order, one tile per stack, and a silently empty
 * page (no error control) when the load fails - using a controlled service instead of a live
 * database.
 *
 * <p>Lives in the default package (like {@code MaterialsView} itself) so it can call
 * {@code MaterialsView.show(Stage, Runnable, MaterialStorageService)} directly.
 *
 * <p>Named with an "IT" suffix (see {@code EctoSalvageViewIT}) so Surefire's default {@code test}
 * goal never selects it - this needs a real desktop/window session. Run explicitly:
 * {@code ./mvnw test -Dtest=MaterialsViewIT}
 */
class MaterialsViewIT extends ApplicationTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void materialsView_rendersOneBlockPerCategoryInServiceOrder() {
        List<MaterialCategory> categories = List.of(
                new MaterialCategory("Basic Crafting Materials", List.of(
                        row(1, 19697, 100), row(1, 19719, 40))),
                new MaterialCategory("Cooking Materials", List.of(
                        row(5, 12142, 3))));
        FakeMaterialStorageService service = new FakeMaterialStorageService(categories, null);

        showWith(service);

        VBox blocks = JavaFxUiSupport.find(this, "#materialsBlocks", VBox.class);

        assertEquals(2, blocks.getChildren().size());
        assertEquals("Basic Crafting Materials", headerOf(blocks, 0));
        assertEquals("Cooking Materials", headerOf(blocks, 1));
        assertEquals(2, gridOf(blocks, 0).getChildren().size());
        assertEquals(1, gridOf(blocks, 1).getChildren().size());

        assertEquals(1, service.callCount, "MaterialsView must load through the injected service exactly once");
    }

    @Test
    void materialsView_rendersAnEmptyPageWhenTheServiceFails() {
        FakeMaterialStorageService service =
                new FakeMaterialStorageService(List.of(), new SQLException("simulated material read failure"));

        showWith(service);

        VBox blocks = JavaFxUiSupport.find(this, "#materialsBlocks", VBox.class);

        // Unchanged pre-extraction behavior: a load failure is only printed, never shown.
        assertTrue(blocks.getChildren().isEmpty());
    }

    /** {@code MaterialsView.show} sets the scene but never shows the stage, so this test does. */
    private void showWith(MaterialStorageService service) {
        interact(() -> {
            MaterialsView.show(stage, () -> {}, service);
            stage.show();
        });
    }

    private static MaterialStorageRow row(int category, int itemId, int count) {
        return new MaterialStorageRow(category, itemId, count, "C:\\icons\\" + itemId + ".png", null, "Basic");
    }

    private static String headerOf(VBox blocks, int index) {
        return ((Label) ((VBox) blocks.getChildren().get(index)).getChildren().get(0)).getText();
    }

    private static GridPane gridOf(VBox blocks, int index) {
        return (GridPane) ((VBox) blocks.getChildren().get(index)).getChildren().get(1);
    }

    private static class FakeMaterialStorageService extends MaterialStorageService {
        private final List<MaterialCategory> categories;
        private final SQLException failure;
        int callCount = 0;

        FakeMaterialStorageService(List<MaterialCategory> categories, SQLException failure) {
            this.categories = categories;
            this.failure = failure;
        }

        @Override
        public List<MaterialCategory> getMaterialStorage() throws SQLException {
            callCount++;
            if (failure != null) throw failure;
            return categories;
        }
    }
}
