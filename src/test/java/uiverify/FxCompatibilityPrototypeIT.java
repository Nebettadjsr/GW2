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
 * Named with an "IT" suffix, and deliberately not starting with "Test" or ending in
 * "Test(s)"/"TestCase", so Surefire's default {@code test} goal never selects it (same
 * convention as {@code api.Gw2ApiLiveSmokeIT}) - this needs a real desktop/window session,
 * which is not always available where {@code ./mvnw test} runs. An earlier version of this
 * class was named {@code TestFxPrototypeIT}: since it starts with "Test", it matched
 * Surefire's default {@code **&#47;Test*.java} inclusion pattern and ran as part of the normal
 * {@code ./mvnw test} suite despite the "IT" suffix - discovered by comparing surefire-report
 * timestamps across a default {@code ./mvnw test} run.
 * Run explicitly: {@code ./mvnw test -Dtest=FxCompatibilityPrototypeIT}
 */
class FxCompatibilityPrototypeIT extends ApplicationTest {

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
