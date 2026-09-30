package web;

import application.AccountRefreshService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.task.BackgroundTaskService;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the account-sync trigger (STORY-API-003, TEST_STRATEGY.md §11 and §35):
 * asynchronous acceptance, validation before anything is scheduled, exactly-once delegation to the
 * existing use case, and the refusal of overlapping syncs.
 *
 * <p>The application service is replaced by a controlled subclass of the real
 * {@link AccountRefreshService} through the controller's own constructor seam, so no GW2 API call and
 * no database write happens here. The step order, persistence and short-circuiting inside
 * {@code refreshAll()} stay covered by {@code application.AccountRefreshServiceTest} and are
 * deliberately not restated at this boundary.
 *
 * <p>The status route is registered alongside the trigger because the trigger's central promise — that
 * it returns while the work is still running and hands back a path that already answers — can only be
 * demonstrated by following that path. The real task facility is used rather than a stub, since the
 * asynchrony is the thing under test.
 */
class AccountSyncApiControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ControlledAccountRefreshService refreshService;
    private BackgroundTaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshService = new ControlledAccountRefreshService();
        taskService = new BackgroundTaskService();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountSyncApiController(refreshService, taskService),
                        new SyncTaskApiController(taskService))
                .setControllerAdvice(new SyncApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        refreshService.release();
        taskService.close();
    }

    // ---------- acceptance ----------

    @Test
    void anAcceptedRequestReturns202WithTheTaskIdentifierAndItsStatusPath() throws Exception {
        String body = mockMvc.perform(post("/api/sync/account"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andReturn().getResponse().getContentAsString();

        String taskId = JsonPath.read(body, "$.taskId");
        String statusUrl = JsonPath.read(body, "$.statusUrl");
        assertEquals("/api/sync/tasks/" + taskId, statusUrl);
        // Acceptance carries no outcome at all - there is nothing in it a caller could misread as
        // "the synchronization succeeded".
        assertFalse(body.contains("SUCCEEDED"), "the acceptance body must not report an outcome: " + body);
    }

    @Test
    void theAdvertisedStatusPathIsAlsoReturnedAsTheLocationHeader() throws Exception {
        mockMvc.perform(post("/api/sync/account"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/sync/tasks/")));
    }

    @Test
    void anEmptyJsonObjectBodyIsAcceptedToo() throws Exception {
        mockMvc.perform(post("/api/sync/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString());
    }

    // ---------- validation, before anything is scheduled ----------

    @Test
    void anUnexpectedRequestFieldIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/sync/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": \"ALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "POST /api/sync/account accepts no request fields; send no body or an empty JSON object"));

        assertNothingWasScheduled();
    }

    @Test
    void anUnreadableBodyIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/sync/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertNothingWasScheduled();
    }

    // ---------- asynchrony ----------

    @Test
    void theTriggerReturnsWhileTheUseCaseCallIsStillBlockedAndTheStatusStaysQueryable() throws Exception {
        refreshService.blockUntilReleased();

        String accepted = mockMvc.perform(post("/api/sync/account"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String statusUrl = JsonPath.read(accepted, "$.statusUrl");

        // The request completed; the use case has not. Both facts are checked before releasing it.
        assertTrue(refreshService.awaitEntered(), "refreshAll() must have been entered on another thread");
        mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.failure").doesNotExist());

        refreshService.release();

        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
        assertEquals(1, refreshService.refreshAllCalls());
    }

    // ---------- delegation and overlapping requests ----------

    @Test
    void eachAcceptedTaskDelegatesToTheExistingUseCaseExactlyOnce() throws Exception {
        String firstStatusUrl = accept();
        assertEquals("SUCCEEDED", awaitTerminalState(firstStatusUrl));
        assertEquals(1, refreshService.refreshAllCalls());

        String secondStatusUrl = accept();
        assertEquals("SUCCEEDED", awaitTerminalState(secondStatusUrl));
        assertEquals(2, refreshService.refreshAllCalls());
        assertNotEquals(firstStatusUrl, secondStatusUrl, "each task must be separately identifiable");
    }

    @Test
    void profitWorkflowUsesTheExistingAccountTaskKeyAndNarrowRefreshUseCase() throws Exception {
        String statusUrl = mockMvc.perform(post("/api/sync/account/crafting-profit"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andReturn().getResponse().getHeader("Location");

        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
        assertEquals(1, refreshService.profitRefreshCalls());
        assertEquals(0, refreshService.refreshAllCalls());
    }

    @Test
    void aSecondTriggerWhileTheFirstIsUnfinishedIsRefusedWithoutDelegatingAgain() throws Exception {
        refreshService.blockUntilReleased();
        String statusUrl = accept();
        assertTrue(refreshService.awaitEntered());
        String runningTaskId = statusUrl.substring(statusUrl.lastIndexOf('/') + 1);

        mockMvc.perform(post("/api/sync/account"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SYNC_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(
                        "A ACCOUNT_SYNC task is already in progress as task " + runningTaskId
                                + "; query its status instead of starting another"));

        assertEquals(1, refreshService.refreshAllCalls(),
                "the refused request must not start a second synchronization");
        refreshService.release();
        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
    }

    @Test
    void aNewSyncIsAcceptedOnceThePreviousOneFinished() throws Exception {
        String firstStatusUrl = accept();
        assertEquals("SUCCEEDED", awaitTerminalState(firstStatusUrl));

        mockMvc.perform(post("/api/sync/account")).andExpect(status().isAccepted());
    }

    private String accept() throws Exception {
        String body = mockMvc.perform(post("/api/sync/account"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.statusUrl");
    }

    /**
     * A rejected request must leave no task behind. Zero delegations proves nothing ran; a following
     * request being accepted rather than refused with 409 proves nothing was admitted either, since an
     * admitted task would still be unfinished.
     */
    private void assertNothingWasScheduled() throws Exception {
        assertEquals(0, refreshService.refreshAllCalls(),
                "a rejected request must not reach the application service");
        mockMvc.perform(post("/api/sync/account")).andExpect(status().isAccepted());
    }

    private String awaitTerminalState(String statusUrl) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String body = mockMvc.perform(get(statusUrl))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String state = JsonPath.read(body, "$.state");
            if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) {
                return state;
            }
            Thread.sleep(1);
        }
        throw new AssertionError(statusUrl + " did not reach a terminal state within " + TIMEOUT);
    }

    /**
     * The real use case with its one entry point overridden: counts calls, and can be made to block so
     * the test controls whether the work is still in flight.
     */
    private static final class ControlledAccountRefreshService extends AccountRefreshService {

        private final AtomicInteger refreshAllCalls = new AtomicInteger();
        private final AtomicInteger profitRefreshCalls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean blocking;

        @Override
        public void refreshAll() throws Exception {
            refreshAllCalls.incrementAndGet();
            entered.countDown();
            if (blocking && !released.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("blocked refreshAll() was never released");
            }
        }

        @Override
        public void refreshCraftingProfitData() {
            profitRefreshCalls.incrementAndGet();
        }

        void blockUntilReleased() {
            blocking = true;
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        int refreshAllCalls() {
            return refreshAllCalls.get();
        }

        int profitRefreshCalls() {
            return profitRefreshCalls.get();
        }
    }
}
