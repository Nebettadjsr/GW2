package uiverify;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import org.testfx.api.FxRobot;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * STORY-UI-001: reusable JavaFX UI-verification building blocks - control lookup, bounded
 * waiting and displayed-state inspection, plus optional screenshot capture - on top of TestFX's
 * {@link FxRobot}. Intended for STORY-DOM-013/014/015's own real-view checks (selection, refresh,
 * empty/error and blocked-row behavior) as well as this story's own smoke test, so those stories
 * do not need to re-derive lookup/waiting/inspection code.
 *
 * <p>Deliberately avoids fixed screen coordinates and arbitrary {@code Thread.sleep} timing:
 * every wait here is a bounded poll against an actual observable condition (a control existing,
 * a table reaching an expected row count, etc.), failing fast with a diagnostic message instead
 * of hanging when the condition never becomes true.
 */
public final class JavaFxUiSupport {

    private JavaFxUiSupport() {}

    /** Default bound for {@link #waitUntil}; generous enough for a background-thread DB reload. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Looks up exactly one node matching {@code query} (a TestFX query: {@code "#id"},
     * {@code ".css-class"}, or exact visible text) via {@code robot}, waiting up to
     * {@code timeout} for it to appear. Fails with a diagnostic message naming the query and
     * timeout, rather than TestFX's own {@code NodeQuery} returning {@code null} silently.
     */
    public static <T extends Node> T find(FxRobot robot, String query, Class<T> type, Duration timeout) {
        waitUntil("control matching \"" + query + "\" to appear", timeout,
                () -> robot.lookup(query).tryQuery().isPresent());
        return robot.lookup(query).queryAs(type);
    }

    public static <T extends Node> T find(FxRobot robot, String query, Class<T> type) {
        return find(robot, query, type, DEFAULT_TIMEOUT);
    }

    /**
     * Polls {@code condition} on a background thread (not the FX Application Thread, so it can
     * safely read JavaFX properties set by {@code Platform.runLater} without deadlocking) until
     * it returns {@code true} or {@code timeout} elapses.
     *
     * @throws AssertionError naming {@code description} and {@code timeout} if the condition
     *                        never becomes true - never hangs indefinitely.
     */
    public static void waitUntil(String description, Duration timeout, Callable<Boolean> condition) {
        try {
            WaitForAsyncUtils.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS, condition);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Timed out after " + timeout + " waiting for: " + description, e);
        }
    }

    /**
     * Waits until {@code table}'s item count equals {@code expectedRowCount}, bounded by
     * {@code timeout}. Useful for asserting an async DB-backed reload finished without polling
     * an arbitrary fixed delay.
     */
    public static void waitForRowCount(FxRobot robot, TableView<?> table, int expectedRowCount, Duration timeout) {
        waitUntil("TableView row count to reach " + expectedRowCount + " (currently " + table.getItems().size() + ")",
                timeout,
                () -> table.getItems().size() == expectedRowCount);
    }

    /**
     * Displayed-state inspection: reads the value each row's cell in {@code table} actually
     * shows for the column with header text {@code columnHeader}, via the column's own
     * {@code cellValueFactory} - the same value the user sees, not a re-derivation from the
     * underlying domain object. Must be called on the FX Application Thread (use
     * {@code robot.interact(...)} or call from a {@code @Test} method after a bounded wait, since
     * TestFX already marshals assertions appropriately).
     */
    public static <S> List<Object> columnValues(TableView<S> table, String columnHeader) {
        TableColumn<S, ?> column = table.getColumns().stream()
                .filter(c -> columnHeader.equals(c.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No TableView column with header \"" + columnHeader + "\". Actual headers: " +
                                table.getColumns().stream().map(TableColumn::getText).toList()));

        List<Object> values = new ArrayList<>();
        for (S row : table.getItems()) {
            values.add(column.getCellObservableValue(row).getValue());
        }
        return values;
    }

    /**
     * Captures {@code node} to a PNG file at {@code outFile}, creating parent directories as
     * needed. Optional per STORY-UI-001's acceptance criteria - useful for diagnosing a failed
     * assertion or documenting a checked behavior, not required for every check.
     */
    public static Path captureScreenshot(Node node, Path outFile) throws IOException {
        WritableImage image = node.snapshot(new SnapshotParameters(), null);

        if (outFile.getParent() != null) {
            java.nio.file.Files.createDirectories(outFile.getParent());
        }

        ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", outFile.toFile());
        return outFile;
    }
}
