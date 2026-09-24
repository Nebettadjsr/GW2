package web.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for the reusable task facility (STORY-API-003, TEST_STRATEGY.md §35.3).
 *
 * <p>Deterministic without sleeping for a guessed duration: a task body signals a latch when it is
 * entered and then blocks on a second latch the test releases, so "still running" and "finished" are
 * observed at points the test controls. Where a terminal state has to be waited for, it is waited for
 * with a bounded poll that fails loudly instead of a fixed sleep.
 *
 * <p>Every documented property of the facility is asserted here, since {@code CURRENT_ARCHITECTURE.md}
 * describes them as facts: the admission rule, the exactly-once execution, the state transitions, the
 * recorded cause, the retention bound and the unknown-identifier answer.
 */
class BackgroundTaskServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private BackgroundTaskService service;
    private final List<BlockingBody> bodies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new BackgroundTaskService();
    }

    @AfterEach
    void tearDown() {
        bodies.forEach(BlockingBody::release);
        service.close();
    }

    @Test
    void submitReturnsWhileTheBodyIsStillBlockedAndTheTaskStaysQueryable() throws Exception {
        BlockingBody body = blockingBody();

        String taskId = service.submit("OP", body);

        // submit() has already returned, so acceptance did not wait for the work.
        assertTrue(body.awaitEntered(), "the body must have started on another thread");
        TaskSnapshot running = service.find(taskId).orElseThrow();
        assertEquals(TaskState.RUNNING, running.state());
        assertFalse(running.state().isTerminal());
        assertNotNull(running.submittedAt());
        assertNotNull(running.startedAt());
        assertNull(running.finishedAt(), "an unfinished task must not carry a finish time");
        assertNull(running.failure());

        body.release();

        TaskSnapshot finished = awaitTerminal(taskId);
        assertEquals(TaskState.SUCCEEDED, finished.state());
        assertNotNull(finished.finishedAt());
        assertNull(finished.failure());
    }

    @Test
    void aFailingBodyIsRecordedAsFailedWithItsOwnCause() throws Exception {
        IOException cause = new IOException("simulated GW2 API failure");

        String taskId = service.submit("OP", () -> {
            throw cause;
        });

        TaskSnapshot task = awaitTerminal(taskId);
        assertEquals(TaskState.FAILED, task.state());
        assertSame(cause, task.failure());
        assertNotNull(task.finishedAt());
    }

    @Test
    void theBodyOfAnAcceptedTaskRunsExactlyOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        String first = service.submit("OP", runs::incrementAndGet);
        awaitTerminal(first);
        String second = service.submit("OP", runs::incrementAndGet);
        awaitTerminal(second);

        assertEquals(2, runs.get(), "each accepted task must run its body once and only once");
        assertNotEquals(first, second, "each accepted task must get its own identifier");
    }

    @Test
    void aSecondSubmissionForAnUnfinishedOperationIsRefusedAndNamesTheRunningTask() throws Exception {
        BlockingBody body = blockingBody();
        String taskId = service.submit("OP", body);
        assertTrue(body.awaitEntered());

        AtomicInteger secondBodyRuns = new AtomicInteger();
        TaskAlreadyRunningException refused = assertThrows(TaskAlreadyRunningException.class,
                () -> service.submit("OP", secondBodyRuns::incrementAndGet));

        assertEquals(taskId, refused.existingTaskId());
        assertEquals("OP", refused.operation());
        assertEquals(0, secondBodyRuns.get(), "the refused submission must not run anything");
    }

    @Test
    void theSameOperationIsAdmittedAgainOnceThePreviousTaskFinished() throws Exception {
        String first = service.submit("OP", () -> {
        });
        awaitTerminal(first);

        String second = service.submit("OP", () -> {
        });

        assertNotEquals(first, second);
        assertEquals(TaskState.SUCCEEDED, awaitTerminal(second).state());
    }

    @Test
    void differentOperationKeysAreAdmittedAtTheSameTimeAndStayIndependentlyIdentifiable() throws Exception {
        BlockingBody blocked = blockingBody();

        String blockedTaskId = service.submit("SLOW_OP", blocked);
        assertTrue(blocked.awaitEntered());
        String failingTaskId = service.submit("OTHER_OP", () -> {
            throw new IllegalStateException("simulated failure");
        });

        assertEquals(TaskState.FAILED, awaitTerminal(failingTaskId).state());
        // The other operation is unaffected by its neighbour's outcome.
        assertEquals(TaskState.RUNNING, service.find(blockedTaskId).orElseThrow().state());
        assertEquals("SLOW_OP", service.find(blockedTaskId).orElseThrow().operation());
        assertEquals("OTHER_OP", service.find(failingTaskId).orElseThrow().operation());
    }

    @Test
    void anIdentifierThisProcessNeverIssuedIsNotFound() {
        assertEquals(Optional.empty(), service.find("3f7c1a90-0000-0000-0000-000000000000"));
    }

    @Test
    void theOldestTerminalRecordIsEvictedOnceRetentionIsFull() throws Exception {
        // One more submission than the documented retention bound; the admission rule forces them to
        // run one at a time, so each is terminal before the next is submitted.
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            String taskId = service.submit("OP", () -> {
            });
            awaitTerminal(taskId);
            taskIds.add(taskId);
        }

        assertEquals(Optional.empty(), service.find(taskIds.getFirst()),
                "the oldest terminal record must have been evicted, and then reads as unknown");
        assertTrue(service.find(taskIds.get(1)).isPresent());
        assertTrue(service.find(taskIds.getLast()).isPresent());
    }

    private BlockingBody blockingBody() {
        BlockingBody body = new BlockingBody();
        bodies.add(body);
        return body;
    }

    private TaskSnapshot awaitTerminal(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            TaskSnapshot task = service.find(taskId).orElseThrow();
            if (task.state().isTerminal()) {
                return task;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("task " + taskId + " did not finish within " + TIMEOUT);
    }

    /** A body that reports when it has been entered and then waits to be let go. */
    private static final class BlockingBody implements BackgroundTaskService.TaskBody {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void run() throws Exception {
            entered.countDown();
            if (!released.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("blocking body was never released");
            }
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }
}
