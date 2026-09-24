package uiverify;

import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * STORY-PERF-001 acceptance measurement (TARGET_ARCHITECTURE.md §33 / TEST_STRATEGY.md §34):
 * measures the <em>real</em> user path - clicking "Crafting Profit Calculator" in the real running
 * application until the real {@code CraftingProfitView} table is completely populated and
 * interactive - against the developer's real PostgreSQL database.
 *
 * <p>Deliberately does <strong>not</strong> install a {@code gw2tool.test.schema} override or seed
 * any fixture, so every repository call hits the same real data the user sees; it only reads.
 *
 * <p>"Complete page" is detected by quiescence, not by the first rows appearing: the FX thread is
 * polled every {@value #POLL_MILLIS} ms for a snapshot of the table (row count plus the identity of
 * its first/last row objects) and the status label, and the page counts as complete at the moment
 * of the <em>last observed change</em>, after which nothing changes for {@value #SETTLE_MILLIS} ms.
 * The settle window itself is excluded from the reported elapsed time. Row-object identity is part
 * of the snapshot on purpose: a second, redundant reload replaces every {@code CraftRow} with a new
 * instance, so it is observable here even when it recomputes the identical numbers.
 *
 * <p>Not part of the default {@code ./mvnw test} run (excluded by its {@code IT} suffix); needs a
 * real desktop session and a reachable Postgres server. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitPageLoadRealDbPerfIT}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CraftingProfitPageLoadRealDbPerfIT extends ApplicationTest {

    private static final long POLL_MILLIS = 50;
    private static final long SETTLE_MILLIS = 3_000;
    private static final long OPEN_TIMEOUT_MILLIS = 240_000;

    @Override
    public void start(Stage stage) throws Exception {
        // Gw2App lives in the unnamed package and so cannot be imported from a named test package.
        Class<?> appClass = Class.forName("Gw2App");
        javafx.application.Application app =
                (javafx.application.Application) appClass.getDeclaredConstructor().newInstance();
        app.start(stage);
    }

    @Test
    void measuresNavigationToCompleteInteractivePageOnRealDatabase() throws Exception {
        List<OpenMeasurement> measurements = new ArrayList<>();

        measurements.add(measureOneOpen("FIRST (after application startup)"));
        goBack();
        measurements.add(measureOneOpen("REPEAT #1"));
        goBack();
        measurements.add(measureOneOpen("REPEAT #2"));

        System.out.println();
        System.out.println("=== STORY-PERF-001 real-UI page load (real DB, All scope) ===");
        long max = 0;
        for (OpenMeasurement m : measurements) {
            System.out.printf("%-34s navigation -> complete page: %6d ms  (first rows visible: %6d ms, "
                            + "rows=%d, post-population changes=%d)%n",
                    m.label(), m.completeMillis(), m.firstRowsMillis(), m.rowCount(), m.changesAfterFirstPopulation());
            max = Math.max(max, m.completeMillis());
        }
        System.out.println("MAXIMUM navigation -> complete page: " + max + " ms (limit per §33: 7000 ms)");
        System.out.println();
    }

    /** Clicks the navigation button and measures until the page stops changing. */
    private OpenMeasurement measureOneOpen(String label) throws Exception {
        long start = System.nanoTime();
        clickOn("Crafting Profit Calculator");

        TableView<?> table = JavaFxUiSupport.find(this, ".table-view", TableView.class,
                java.time.Duration.ofSeconds(30));
        Label status = JavaFxUiSupport.find(this, "#craftingProfitStatusLabel", Label.class,
                java.time.Duration.ofSeconds(30));

        String lastSnapshot = null;
        long lastChangeNanos = start;
        long firstRowsNanos = -1;
        int changesAfterFirstPopulation = -1;
        int rowCount = 0;

        while (true) {
            String snapshot = onFx(() -> snapshotOf(table, status));
            long now = System.nanoTime();

            if (!snapshot.equals(lastSnapshot)) {
                lastSnapshot = snapshot;
                lastChangeNanos = now;
                int size = rowCountOf(snapshot);
                if (size > 0) {
                    if (firstRowsNanos < 0) {
                        firstRowsNanos = now;
                        changesAfterFirstPopulation = 0;
                    } else {
                        changesAfterFirstPopulation++;
                    }
                    rowCount = size;
                }
            }

            if (firstRowsNanos > 0 && now - lastChangeNanos > SETTLE_MILLIS * 1_000_000L) {
                break;
            }
            if (now - start > OPEN_TIMEOUT_MILLIS * 1_000_000L) {
                throw new AssertionError(label + ": page never reached a stable populated state within "
                        + OPEN_TIMEOUT_MILLIS + " ms (last snapshot: " + lastSnapshot + ")");
            }
            Thread.sleep(POLL_MILLIS);
        }

        // "Interactive", not merely painted: the table must answer a real read of the values it
        // displays, through the same cellValueFactory chain the user's eyes go through.
        long interactiveProbeStart = System.nanoTime();
        List<Object> names = onFx(() -> JavaFxUiSupport.columnValues(table, "Item"));
        List<Object> profits = onFx(() -> JavaFxUiSupport.columnValues(table, "Total profit"));
        long interactiveProbeMillis = (System.nanoTime() - interactiveProbeStart) / 1_000_000;
        if (names.isEmpty() || names.size() != profits.size()) {
            throw new AssertionError(label + ": populated table did not answer a displayed-value read");
        }

        OpenMeasurement m = new OpenMeasurement(
                label,
                (lastChangeNanos - start) / 1_000_000,
                (firstRowsNanos - start) / 1_000_000,
                rowCount,
                changesAfterFirstPopulation);
        System.out.println("[" + label + "] complete=" + m.completeMillis() + " ms, firstRows="
                + m.firstRowsMillis() + " ms, rows=" + m.rowCount()
                + ", changesAfterFirstPopulation=" + m.changesAfterFirstPopulation()
                + ", interactiveProbe=" + interactiveProbeMillis + " ms");
        return m;
    }

    private void goBack() {
        clickOn("← Back");
        WaitForAsyncUtils.waitForFxEvents();
        JavaFxUiSupport.waitUntil("home screen to return", java.time.Duration.ofSeconds(30),
                () -> lookup("Crafting Profit Calculator").tryQuery().isPresent());
    }

    private static String snapshotOf(TableView<?> table, Label status) {
        List<?> items = table.getItems();
        int size = items.size();
        int firstId = size > 0 ? System.identityHashCode(items.get(0)) : 0;
        int lastId = size > 0 ? System.identityHashCode(items.get(size - 1)) : 0;
        return size + "|" + firstId + "|" + lastId + "|" + status.getText();
    }

    private static int rowCountOf(String snapshot) {
        return Integer.parseInt(snapshot.substring(0, snapshot.indexOf('|')));
    }

    private <T> T onFx(Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, WaitForAsyncUtils.asyncFx(callable));
    }

    private record OpenMeasurement(String label, long completeMillis, long firstRowsMillis,
                                   int rowCount, int changesAfterFirstPopulation) {
    }
}
