package uiverify;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * STORY-UI-001: minimal compatibility prototype, run and passing before any further TestFX
 * tooling was adopted. Proves TestFX 4.0.18 (the only released version; the project has no
 * newer choice available) actually drives this project's real dependency versions - JavaFX
 * 25.0.2 (win classifier) and Java 25 - rather than assuming compatibility. Not part of the
 * reusable harness itself; kept as recorded evidence.
 *
 * Named with an "IT" suffix so Surefire's default {@code test} goal never selects it (same
 * convention as {@code api.Gw2ApiLiveSmokeIT}) - this needs a real desktop/window session,
 * which is not always available where {@code ./mvnw test} runs.
 * Run explicitly: {@code ./mvnw test -Dtest=TestFxPrototypeIT}
 */
class TestFxPrototypeIT extends ApplicationTest {

    private Button button;
    private Label label;

    @Override
    public void start(Stage stage) {
        label = new Label("before");
        button = new Button("Click me");
        button.setOnAction(e -> label.setText("after"));

        stage.setScene(new Scene(new VBox(10, button, label), 200, 100));
        stage.show();
    }

    @Test
    void clickingButtonUpdatesLabel_provingRealSceneGraphInteraction() {
        assertEquals("before", label.getText());

        clickOn(button);

        assertEquals("after", label.getText());
    }
}
