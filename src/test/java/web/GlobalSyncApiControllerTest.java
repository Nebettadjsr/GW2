package web;

import application.AccountRefreshService;
import application.GlobalDataRefreshService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.task.BackgroundTaskService;

import java.io.IOException;
import java.sql.SQLException;
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
 * HTTP contract tests for the global-sync trigger (STORY-API-004, TEST_STRATEGY.md §11, §35.1 and
 * §35.3): asynchronous acceptance, validation before anything is scheduled, exactly-once delegation
 * to the existing use case, the refusal of overlapping global syncs, and the independence of the
 * {@code GLOBAL_SYNC} operation key from {@code ACCOUNT_SYNC}.
 *
 * <p>The application service is replaced by a controlled subclass of the real
 * {@link GlobalDataRefreshService} through the controller's own constructor seam, so no GW2 API call,
 * no database write and no {@code crafting_graph_cache.json} rewrite happens here. The step order
 * (tradeable items, global recipes, graph rebuild) and the short-circuiting inside {@code refreshAll()}
 * stay covered by {@code application.GlobalDataRefreshServiceTest} and are deliberately not restated
 * at this boundary.
 *
 * <p>The status route is registered alongside the trigger because the trigger's central promise — that
 * it returns while the work is still running and hands back a path that already answers — can only be
 * demonstrated by following that path. The real task facility is used rather than a stub, since the
 * asynchrony and the admission rule are the thing under test (§35.3).
 */
class GlobalSyncApiControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ControlledGlobalDataRefreshService refreshService;
    private BackgroundTaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshService = new ControlledGlobalDataRefreshService();
        taskService = new BackgroundTaskService();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GlobalSyncApiController(refreshService, taskService),
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
        String body = mockMvc.perform(post("/api/sync/global"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"))
                .andReturn().getResponse().getContentAsString();

        String taskId = JsonPath.read(body, "$.taskId");
        String statusUrl = JsonPath.read(body, "$.statusUrl");
        assertEquals("/api/sync/tasks/" + taskId, statusUrl);
        // Acceptance carries no outcome at all - there is nothing in it a caller could misread as
        // "the global synchronization succeeded".
        assertFalse(body.contains("SUCCEEDED"), "the acceptance body must not report an outcome: " + body);
    }

    @Test
    void theAdvertisedStatusPathIsAlsoReturnedAsTheLocationHeader() throws Exception {
        mockMvc.perform(post("/api/sync/global"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/sync/tasks/")));
    }

    @Test
    void anEmptyJsonObjectBodyIsAcceptedToo() throws Exception {
        mockMvc.perform(post("/api/sync/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString());
    }

    // ---------- validation, before anything is scheduled ----------

    @Test
    void anUnexpectedRequestFieldIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/sync/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rebuildGraph\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                // The shared rule names this route, not the account one.
                .andExpect(jsonPath("$.message").value(
                        "POST /api/sync/global accepts no request fields; send no body or an empty JSON object"));

        assertNothingWasScheduled();
    }

    @Test
    void anUnreadableBodyIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/sync/global")
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

        String accepted = mockMvc.perform(post("/api/sync/global"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String statusUrl = JsonPath.read(accepted, "$.statusUrl");

        // The request completed; the use case has not. Both facts are checked before releasing it.
        assertTrue(refreshService.awaitEntered(), "refreshAll() must have been entered on another thread");
        mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"))
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
    void aSecondTriggerWhileTheFirstIsUnfinishedIsRefusedWithoutDelegatingAgain() throws Exception {
        refreshService.blockUntilReleased();
        String statusUrl = accept();
        assertTrue(refreshService.awaitEntered());
        String runningTaskId = statusUrl.substring(statusUrl.lastIndexOf('/') + 1);

        mockMvc.perform(post("/api/sync/global"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SYNC_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(
                        "A GLOBAL_SYNC task is already in progress as task " + runningTaskId
                                + "; query its status instead of starting another"));

        assertEquals(1, refreshService.refreshAllCalls(),
                "the refused request must not start a second global synchronization");
        refreshService.release();
        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
    }

    @Test
    void aNewGlobalSyncIsAcceptedOnceThePreviousOneFinished() throws Exception {
        String firstStatusUrl = accept();
        assertEquals("SUCCEEDED", awaitTerminalState(firstStatusUrl));

        mockMvc.perform(post("/api/sync/global")).andExpect(status().isAccepted());
    }

    /**
     * The documented interaction with the other task type: {@code GLOBAL_SYNC} and
     * {@code ACCOUNT_SYNC} are different admission keys, so an unfinished global sync refuses only
     * another global sync — the account trigger is unaffected, and the two tasks stay separately
     * identifiable through the one shared status route.
     */
    @Test
    void anUnfinishedGlobalSyncDoesNotBlockTheAccountTrigger() throws Exception {
        ControlledAccountRefreshService accountService = new ControlledAccountRefreshService();
        MockMvc bothTriggers = MockMvcBuilders
                .standaloneSetup(new GlobalSyncApiController(refreshService, taskService),
                        new AccountSyncApiController(accountService, taskService),
                        new SyncTaskApiController(taskService))
                .setControllerAdvice(new SyncApiExceptionHandler())
                .build();

        refreshService.blockUntilReleased();
        String globalBody = bothTriggers.perform(post("/api/sync/global"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertTrue(refreshService.awaitEntered());

        String accountBody = bothTriggers.perform(post("/api/sync/account"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andReturn().getResponse().getContentAsString();

        String globalStatusUrl = JsonPath.read(globalBody, "$.statusUrl");
        String accountStatusUrl = JsonPath.read(accountBody, "$.statusUrl");
        assertNotEquals(globalStatusUrl, accountStatusUrl, "the two tasks must be separately identifiable");

        // The account sync runs to completion while the global one is still held at its latch.
        assertEquals("SUCCEEDED", awaitTerminalState(bothTriggers, accountStatusUrl));
        assertEquals(1, accountService.refreshAllCalls());
        bothTriggers.perform(get(accountStatusUrl))
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"));
        bothTriggers.perform(get(globalStatusUrl))
                .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"))
                .andExpect(jsonPath("$.state").value("RUNNING"));

        refreshService.release();
        assertEquals("SUCCEEDED", awaitTerminalState(globalStatusUrl));
    }

    // ---------- failure, reported by the shared status route ----------

    @Test
    void aFailedGlobalSyncIsReportedWithoutTheExceptionsOwnText() throws Exception {
        refreshService.failWith(new IOException(
                "GET https://api.guildwars2.com/v2/commerce/prices failed for host api.guildwars2.com"));

        String statusUrl = accept();

        assertEquals("FAILED", awaitTerminalState(statusUrl));
        String body = mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"))
                .andExpect(jsonPath("$.failure.error").value("SYNC_FAILED"))
                .andExpect(jsonPath("$.failure.message").value(
                        "Synchronization failed; steps that had already completed were not rolled back"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("guildwars2.com"), "the failure must not disclose the GW2 endpoint: " + body);
        assertFalse(body.contains("SUCCEEDED"), "a failure body must not carry completion wording: " + body);
    }

    @Test
    void aDataStoreFailureKeepsTheSharedCodeTheCalculationRoutesUse() throws Exception {
        refreshService.failWith(new SQLException("jdbc:postgresql://localhost:5432/gw2 connection refused"));

        String statusUrl = accept();

        assertEquals("FAILED", awaitTerminalState(statusUrl));
        String body = mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.error").value("DATA_STORE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc:"), "the failure must not disclose the JDBC URL: " + body);
    }

    private String accept() throws Exception {
        String body = mockMvc.perform(post("/api/sync/global"))
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
        mockMvc.perform(post("/api/sync/global")).andExpect(status().isAccepted());
    }

    private String awaitTerminalState(String statusUrl) throws Exception {
        return awaitTerminalState(mockMvc, statusUrl);
    }

    private static String awaitTerminalState(MockMvc mvc, String statusUrl) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String body = mvc.perform(get(statusUrl))
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
     * The real use case with its one entry point overridden: counts calls, can be made to fail, and
     * can be made to block so the test controls whether the work is still in flight. Constructing the
     * real superclass is safe — its collaborators open no connection until a step runs, and no step
     * ever does here.
     */
    private static final class ControlledGlobalDataRefreshService extends GlobalDataRefreshService {

        private final AtomicInteger refreshAllCalls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean blocking;
        private volatile Exception failure;

        @Override
        public RefreshResult refreshAll() throws Exception {
            refreshAllCalls.incrementAndGet();
            entered.countDown();
            if (blocking && !released.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("blocked refreshAll() was never released");
            }
            if (failure != null) {
                throw failure;
            }
            return new RefreshResult(false, false, false);
        }

        void blockUntilReleased() {
            blocking = true;
        }

        void failWith(Exception e) {
            failure = e;
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
    }

    /** Counts account-sync delegations, for the cross-operation independence check only. */
    private static final class ControlledAccountRefreshService extends AccountRefreshService {

        private final AtomicInteger refreshAllCalls = new AtomicInteger();

        @Override
        public void refreshAll() {
            refreshAllCalls.incrementAndGet();
        }

        int refreshAllCalls() {
            return refreshAllCalls.get();
        }
    }
}
