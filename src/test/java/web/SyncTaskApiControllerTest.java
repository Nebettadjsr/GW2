package web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.task.BackgroundTaskService;
import web.task.TaskState;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the task-status route (STORY-API-003, TEST_STRATEGY.md §11 and §35): the
 * states it distinguishes, what it says about a failure, and its answer for an identifier it cannot
 * resolve.
 *
 * <p>Tasks are submitted straight into the real task facility with controlled bodies, so each state
 * is produced deliberately rather than waited for; the trigger route is not involved, since this route
 * is shared by every operation and is not specific to account synchronization. The flow from trigger to
 * status is covered by {@link AccountSyncApiControllerTest}.
 */
class SyncTaskApiControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private BackgroundTaskService taskService;
    private MockMvc mockMvc;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskService = new BackgroundTaskService();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SyncTaskApiController(taskService))
                .setControllerAdvice(new SyncApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        taskService.close();
    }

    @Test
    void aRunningTaskIsReportedAsRunningWithNoFinishTimeAndNoFailure() throws Exception {
        String taskId = submitBlockedTask("ACCOUNT_SYNC");

        mockMvc.perform(get("/api/sync/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.submittedAt").isString())
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.failure").doesNotExist());
    }

    @Test
    void aSucceededTaskIsReportedAsSucceededWithAFinishTimeAndNoFailure() throws Exception {
        String taskId = submitAndAwaitTerminal("ACCOUNT_SYNC", () -> {
        });

        mockMvc.perform(get("/api/sync/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.finishedAt").isString())
                .andExpect(jsonPath("$.failure").doesNotExist());
    }

    @Test
    void aFailedTaskIsReportedAsFailedAndSaysCompletedStepsWereNotRolledBack() throws Exception {
        String taskId = submitAndAwaitTerminal("ACCOUNT_SYNC", () -> {
            throw new IOException("GW2 API returned 503 for /v2/characters, connection to 10.1.2.3 refused");
        });

        String body = mockMvc.perform(get("/api/sync/tasks/" + taskId))
                // A failed task is still a successful lookup: the outcome lives in the body.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.finishedAt").isString())
                .andExpect(jsonPath("$.failure.error").value("SYNC_FAILED"))
                .andExpect(jsonPath("$.failure.message").value(
                        "Synchronization failed; steps that had already completed were not rolled back"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("SUCCEEDED"), "a failed task must not read as completed: " + body);
        assertFalse(body.contains("10.1.2.3"),
                "the failure's own detail must stay server-side per the disclosure convention: " + body);
        assertFalse(body.contains("/v2/characters"), "leaked failure detail: " + body);
    }

    @Test
    void aDataStoreFailureKeepsTheCodeTheCalculationRoutesAlreadyUseForIt() throws Exception {
        String taskId = submitAndAwaitTerminal("ACCOUNT_SYNC", () -> {
            throw new SQLException("connection to jdbc:postgresql://localhost:5432/gw2 refused");
        });

        String body = mockMvc.perform(get("/api/sync/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.failure.error").value("DATA_STORE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc:postgresql"), "the connection string must not reach the caller: " + body);
    }

    @Test
    void concurrentTasksAreIndependentlyIdentifiableThroughTheirOwnIdentifiers() throws Exception {
        String blockedTaskId = submitBlockedTask("ACCOUNT_SYNC");
        String failedTaskId = submitAndAwaitTerminal("OTHER_SYNC", () -> {
            throw new IOException("simulated failure");
        });

        mockMvc.perform(get("/api/sync/tasks/" + blockedTaskId))
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andExpect(jsonPath("$.state").value("RUNNING"));
        mockMvc.perform(get("/api/sync/tasks/" + failedTaskId))
                .andExpect(jsonPath("$.operation").value("OTHER_SYNC"))
                .andExpect(jsonPath("$.state").value("FAILED"));
    }

    @Test
    void anIdentifierThisProcessCannotResolveIs404() throws Exception {
        mockMvc.perform(get("/api/sync/tasks/3f7c1a90-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "No task with that identifier is known to this server; task records are held in "
                                + "memory only and do not survive a restart"));
    }

    @Test
    void anUnresolvableIdentifierIsNotEchoedBackToTheCaller() throws Exception {
        String body = mockMvc.perform(get("/api/sync/tasks/not-a-task-id"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("not-a-task-id"), "caller-supplied text must not be reflected: " + body);
    }

    private String submitBlockedTask(String operation) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);

        String taskId = taskService.submit(operation, () -> {
            entered.countDown();
            if (!release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("blocked task was never released");
            }
        });

        assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the task body must have started");
        return taskId;
    }

    private String submitAndAwaitTerminal(String operation, BackgroundTaskService.TaskBody body)
            throws InterruptedException {
        String taskId = taskService.submit(operation, body);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            TaskState state = taskService.find(taskId).orElseThrow().state();
            if (state.isTerminal()) {
                return taskId;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("task " + taskId + " did not finish within " + TIMEOUT);
    }
}
