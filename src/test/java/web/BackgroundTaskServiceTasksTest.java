package web;

import application.BackgroundTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import web.task.BackgroundTaskService;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014: the adapter between the shared in-process task facility and the application
 * layer's {@link BackgroundTasks} port.
 *
 * <p>What has to hold for the coordinators built on that port, and is asserted here against the
 * <em>real</em> {@link BackgroundTaskService} rather than a fake, because the port's contract is a
 * promise about the real facility:
 *
 * <ul>
 *   <li>a second unfinished task for one operation key is refused loudly and carries the existing
 *       identifier, which is how a coordinator recognises another entry point's writer;</li>
 *   <li>a terminal state is reported as terminal, and a failure as failed with the same stable code
 *       the task-status route publishes for the same snapshot — never the exception's own text;</li>
 *   <li>an identifier this process cannot resolve answers empty, which is what the freshness
 *       envelope turns into bounded recovery information.</li>
 * </ul>
 *
 * <p>Deterministic: a body signals a latch when entered and blocks on a second one the test
 * releases, and terminal states are waited for with a bounded poll that fails loudly.
 */
class BackgroundTaskServiceTasksTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private BackgroundTaskService facility;
    private BackgroundTasks tasks;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        facility = new BackgroundTaskService();
        tasks = new BackgroundTaskServiceTasks(facility);
        release = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        facility.close();
    }

    @Test
    void anAdmittedTaskRunsAndIsReportedRunningThenSucceeded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        String taskId = tasks.submit("ADAPTER_OP", () -> {
            entered.countDown();
            assertTrue(release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "never released");
        });

        assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the body must have started");
        BackgroundTasks.Status running = tasks.find(taskId).orElseThrow();
        assertEquals(taskId, running.taskId());
        assertEquals("ADAPTER_OP", running.operation());
        assertEquals("RUNNING", running.state());
        assertFalse(running.terminal(), "a running task must not be reported terminal");
        assertFalse(running.failed());
        assertNull(running.failureCode());
        assertNull(running.failureMessage());

        release.countDown();

        BackgroundTasks.Status done = awaitTerminal(taskId);
        assertEquals("SUCCEEDED", done.state());
        assertTrue(done.terminal());
        assertFalse(done.failed());
        assertNull(done.failureCode(), "a succeeded task carries no failure code");
    }

    @Test
    void aSecondUnfinishedTaskForOneOperationIsRefusedWithTheExistingIdentifier() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        String first = tasks.submit("ADAPTER_OP", () -> {
            entered.countDown();
            assertTrue(release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "never released");
        });
        assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the first body must have started");

        BackgroundTasks.OperationAlreadyRunning refused = assertThrows(
                BackgroundTasks.OperationAlreadyRunning.class,
                () -> tasks.submit("ADAPTER_OP", () -> {
                    throw new AssertionError("the refused body must never run");
                }));

        assertEquals("ADAPTER_OP", refused.operation());
        assertEquals(first, refused.existingTaskId(),
                "the coordinator needs the identifier of the writer that is already running");
    }

    @Test
    void aDifferentOperationKeyIsAdmittedAlongsideAnUnfinishedOne() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        tasks.submit("ACCOUNT_LIKE", () -> {
            entered.countDown();
            assertTrue(release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "never released");
        });
        assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the first body must have started");

        String second = tasks.submit("PRICE_LIKE", () -> { });

        assertEquals("PRICE_LIKE", awaitTerminal(second).operation(),
                "account and price coordination must not block each other");
    }

    @Test
    void aDataStoreFailureIsReportedWithTheSameCodeTheStatusRoutePublishes() throws Exception {
        String taskId = tasks.submit("ADAPTER_OP", () -> {
            throw new SQLException("connection refused to " + Instant.now());
        });

        BackgroundTasks.Status failed = awaitTerminal(taskId);
        assertEquals("FAILED", failed.state());
        assertTrue(failed.terminal());
        assertTrue(failed.failed());
        assertEquals(BackgroundTaskServiceTasks.DATA_STORE_FAILURE_CODE, failed.failureCode());
        assertFalse(failed.failureMessage().contains("connection refused"),
                "the exception's own text must not reach a caller");
    }

    @Test
    void anyOtherFailureIsReportedAsASyncFailure() throws Exception {
        String taskId = tasks.submit("ADAPTER_OP", () -> {
            throw new IllegalStateException("upstream refused");
        });

        BackgroundTasks.Status failed = awaitTerminal(taskId);
        assertTrue(failed.failed());
        assertEquals(BackgroundTaskServiceTasks.SYNC_FAILURE_CODE, failed.failureCode());
        assertFalse(failed.failureMessage().contains("upstream refused"));
    }

    @Test
    void anIdentifierThisProcessCannotResolveAnswersEmpty() {
        assertEquals(Optional.empty(), tasks.find("no-such-task"),
                "an unresolvable identifier must be distinguishable from a known one");
    }

    private BackgroundTasks.Status awaitTerminal(String taskId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            BackgroundTasks.Status status = tasks.find(taskId).orElseThrow();
            if (status.terminal()) return status;
            Thread.sleep(10);
        }
        throw new AssertionError(taskId + " never reached a terminal state within " + TIMEOUT);
    }
}
