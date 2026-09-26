package web;

import application.TradingPostPriceRefreshService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
 * HTTP contract tests for the price-refresh trigger (STORY-API-005, TEST_STRATEGY.md §11, §35.1 and
 * §35.3): asynchronous acceptance of each variant, the required unambiguous variant, validation
 * before anything is scheduled, exactly-once delegation to the requested use-case method with no call
 * of the other one, and the two variant keys' exclusion of themselves but not of each other.
 *
 * <p>The application service is replaced by a controlled subclass of the real
 * {@link TradingPostPriceRefreshService} through the controller's own constructor seam, so no GW2 API
 * call and no {@code tp_prices} write happens here. Which items each variant selects, the batching and
 * the persistence stay covered by {@code application.TradingPostPriceRefreshServiceTest} and the
 * {@code sync} suites, and are deliberately not restated at this boundary.
 *
 * <p>The status route is registered alongside the trigger because the trigger's central promise — that
 * it returns while the work is still running and hands back a path that already answers — can only be
 * demonstrated by following that path. The real task facility is used rather than a stub, since the
 * asynchrony and the admission rule are the thing under test (§35.3).
 */
class PriceRefreshApiControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ControlledPriceRefreshService refreshService;
    private BackgroundTaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshService = new ControlledPriceRefreshService();
        taskService = new BackgroundTaskService();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PriceRefreshApiController(refreshService, taskService),
                        new SyncTaskApiController(taskService))
                .setControllerAdvice(new SyncApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        refreshService.release();
        taskService.close();
    }

    // ---------- acceptance, per variant ----------

    @Test
    void theProfitVariantIsAcceptedWith202TheTaskIdentifierAndItsStatusPath() throws Exception {
        String body = perform("PROFIT")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.operation").value("PRICE_REFRESH_PROFIT"))
                .andReturn().getResponse().getContentAsString();

        String taskId = JsonPath.read(body, "$.taskId");
        assertEquals("/api/sync/tasks/" + taskId, JsonPath.read(body, "$.statusUrl"));
        // Acceptance carries no outcome at all - there is nothing in it a caller could misread as
        // "the prices were refreshed".
        assertFalse(body.contains("SUCCEEDED"), "the acceptance body must not report an outcome: " + body);
    }

    @Test
    void theDiscoveryVariantIsAcceptedUnderItsOwnOperationKey() throws Exception {
        perform("DISCOVERY")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.operation").value("PRICE_REFRESH_DISCOVERY"))
                .andExpect(jsonPath("$.statusUrl", startsWith("/api/sync/tasks/")));
    }

    @Test
    void theAdvertisedStatusPathIsAlsoReturnedAsTheLocationHeader() throws Exception {
        perform("PROFIT")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/sync/tasks/")));
    }

    // ---------- delegation: the requested variant, exactly once, and only it ----------

    @Test
    void theProfitVariantCallsRefreshForProfitExactlyOnceAndNeverTheDiscoveryOne() throws Exception {
        assertEquals("SUCCEEDED", awaitTerminalState(accept("PROFIT")));

        assertEquals(1, refreshService.profit().calls());
        assertEquals(0, refreshService.discovery().calls(), "the other variant must not be refreshed");
    }

    @Test
    void theDiscoveryVariantCallsRefreshForDiscoveryExactlyOnceAndNeverTheProfitOne() throws Exception {
        assertEquals("SUCCEEDED", awaitTerminalState(accept("DISCOVERY")));

        assertEquals(1, refreshService.discovery().calls());
        assertEquals(0, refreshService.profit().calls(), "the other variant must not be refreshed");
    }

    // ---------- validation, before anything is scheduled ----------

    @Test
    void anAbsentBodyIsRejectedBecauseTheVariantIsRequiredAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/prices/refresh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "variant is required: the Profit and Discovery price refreshes cover different "
                                + "item sets, so one of PROFIT, DISCOVERY must be named"));

        assertNothingWasScheduled();
    }

    @Test
    void anEmptyJsonObjectIsRejectedToo() throws Exception {
        mockMvc.perform(post("/api/prices/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        assertNothingWasScheduled();
    }

    /**
     * Every value that does not name one of the two supported variants is refused the same way —
     * including a combined "refresh everything" value, which no use case implements and which this
     * route deliberately does not invent, a differently-cased variant, a blank value and a non-string
     * value.
     */
    @Test
    void anUnsupportedVariantValueIsRejectedAndNothingIsScheduled() throws Exception {
        for (String value : new String[] {"\"ALL\"", "\"profit\"", "\"\"", "5"}) {
            mockMvc.perform(post("/api/prices/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"variant\": " + value + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        assertNothingWasScheduled();
    }

    @Test
    void anUnexpectedRequestFieldIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/prices/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variant\": \"PROFIT\", \"itemIds\": [1, 2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                // Item selection belongs to the collectors; the message names this route and the field
                // it refused, so a caller cannot believe it narrowed the refresh.
                .andExpect(jsonPath("$.message").value(
                        "POST /api/prices/refresh accepts only the variant field; unexpected: itemIds"));

        assertNothingWasScheduled();
    }

    @Test
    void anUnreadableBodyIsRejectedAndNothingIsScheduled() throws Exception {
        mockMvc.perform(post("/api/prices/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertNothingWasScheduled();
    }

    // ---------- asynchrony ----------

    @Test
    void theTriggerReturnsWhileTheRefreshIsStillBlockedAndTheStatusStaysQueryable() throws Exception {
        refreshService.profit().blockUntilReleased();

        String statusUrl = accept("PROFIT");

        // The request completed; the refresh has not. Both facts are checked before releasing it.
        assertTrue(refreshService.profit().awaitEntered(),
                "refreshForProfit() must have been entered on another thread");
        mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("PRICE_REFRESH_PROFIT"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.failure").doesNotExist());

        refreshService.profit().release();

        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
        assertEquals(1, refreshService.profit().calls());
    }

    // ---------- admission: one unfinished task per variant ----------

    @Test
    void aSecondRequestForTheSameVariantIsRefusedWithoutRefreshingAgain() throws Exception {
        refreshService.profit().blockUntilReleased();
        String statusUrl = accept("PROFIT");
        assertTrue(refreshService.profit().awaitEntered());
        String runningTaskId = statusUrl.substring(statusUrl.lastIndexOf('/') + 1);

        perform("PROFIT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SYNC_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(
                        "A PRICE_REFRESH_PROFIT task is already in progress as task " + runningTaskId
                                + "; query its status instead of starting another"));

        assertEquals(1, refreshService.profit().calls(),
                "the refused request must not start a second profit refresh");
        refreshService.profit().release();
        assertEquals("SUCCEEDED", awaitTerminalState(statusUrl));
    }

    /**
     * The documented interaction between the two variants: they are different admission keys, so an
     * unfinished profit refresh refuses only another profit refresh. The discovery variant is
     * unaffected and the two tasks stay separately identifiable through the one shared status route.
     * This is the facility's existing per-key rule applied, not a claim that the two concurrent
     * refreshes cannot interact — what they share is recorded in {@code CURRENT_ARCHITECTURE.md} §5.9.
     */
    @Test
    void anUnfinishedProfitRefreshDoesNotBlockTheDiscoveryVariant() throws Exception {
        refreshService.profit().blockUntilReleased();
        String profitStatusUrl = accept("PROFIT");
        assertTrue(refreshService.profit().awaitEntered());

        String discoveryStatusUrl = accept("DISCOVERY");
        assertNotEquals(profitStatusUrl, discoveryStatusUrl, "the two tasks must be separately identifiable");

        // The discovery refresh runs to completion while the profit one is still held at its latch.
        assertEquals("SUCCEEDED", awaitTerminalState(discoveryStatusUrl));
        assertEquals(1, refreshService.discovery().calls());
        mockMvc.perform(get(profitStatusUrl))
                .andExpect(jsonPath("$.operation").value("PRICE_REFRESH_PROFIT"))
                .andExpect(jsonPath("$.state").value("RUNNING"));

        refreshService.profit().release();
        assertEquals("SUCCEEDED", awaitTerminalState(profitStatusUrl));
    }

    @Test
    void aNewRefreshOfTheSameVariantIsAcceptedOnceThePreviousOneFinished() throws Exception {
        assertEquals("SUCCEEDED", awaitTerminalState(accept("PROFIT")));

        String secondStatusUrl = accept("PROFIT");
        assertEquals("SUCCEEDED", awaitTerminalState(secondStatusUrl));
        assertEquals(2, refreshService.profit().calls(), "each accepted task refreshes exactly once");
    }

    // ---------- failure, reported by the shared status route ----------

    @Test
    void aFailedRefreshIsReportedWithoutTheExceptionsOwnText() throws Exception {
        refreshService.discovery().failWith(new IOException(
                "GET https://api.guildwars2.com/v2/commerce/prices failed for host api.guildwars2.com"));

        String statusUrl = accept("DISCOVERY");

        assertEquals("FAILED", awaitTerminalState(statusUrl));
        String body = mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("PRICE_REFRESH_DISCOVERY"))
                .andExpect(jsonPath("$.failure.error").value("SYNC_FAILED"))
                // The shared no-rollback wording is exactly true here too: the refresh commits per
                // fetched batch, so earlier batches stay written. It claims no step-level progress.
                .andExpect(jsonPath("$.failure.message").value(
                        "Synchronization failed; steps that had already completed were not rolled back"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("guildwars2.com"), "the failure must not disclose the GW2 endpoint: " + body);
        assertFalse(body.contains("SUCCEEDED"), "a failure body must not carry completion wording: " + body);
    }

    @Test
    void aDataStoreFailureKeepsTheSharedCodeTheOtherRoutesUse() throws Exception {
        refreshService.profit().failWith(new SQLException("jdbc:postgresql://localhost:5432/gw2 connection refused"));

        String statusUrl = accept("PROFIT");

        assertEquals("FAILED", awaitTerminalState(statusUrl));
        String body = mockMvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.error").value("DATA_STORE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc:"), "the failure must not disclose the JDBC URL: " + body);
    }

    private ResultActions perform(String variant) throws Exception {
        return mockMvc.perform(post("/api/prices/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variant\": \"" + variant + "\"}"));
    }

    private String accept(String variant) throws Exception {
        String body = perform(variant)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.statusUrl");
    }

    /**
     * A rejected request must leave no task behind. Zero delegations of either variant proves nothing
     * ran; a following request being accepted rather than refused with 409 proves nothing was admitted
     * either, since an admitted task would still be unfinished.
     */
    private void assertNothingWasScheduled() throws Exception {
        assertEquals(0, refreshService.profit().calls(),
                "a rejected request must not reach the application service");
        assertEquals(0, refreshService.discovery().calls(),
                "a rejected request must not reach the application service");
        perform("PROFIT").andExpect(status().isAccepted());
        perform("DISCOVERY").andExpect(status().isAccepted());
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
     * The real use case with both entry points overridden. Each variant is controlled and counted
     * <em>separately</em>, which is what makes "the other variant was never refreshed" observable and
     * what lets one variant be held at a latch while the other runs to completion. Constructing the
     * real superclass is safe — its gateway wraps static utilities that open no connection until a
     * refresh runs, and none ever does here.
     */
    private static final class ControlledPriceRefreshService extends TradingPostPriceRefreshService {

        private final Variant profit = new Variant("refreshForProfit()");
        private final Variant discovery = new Variant("refreshForDiscovery()");

        @Override
        public void refreshForProfit() throws Exception {
            profit.invoke();
        }

        @Override
        public void refreshForDiscovery() throws Exception {
            discovery.invoke();
        }

        Variant profit() {
            return profit;
        }

        Variant discovery() {
            return discovery;
        }

        /** Unblocks anything still latched, so a failed test cannot leave a thread waiting. */
        void release() {
            profit.release();
            discovery.release();
        }
    }

    /** One variant's call count and its optional block/fail behavior. */
    private static final class Variant {

        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean blocking;
        private volatile Exception failure;

        Variant(String name) {
            this.name = name;
        }

        void invoke() throws Exception {
            calls.incrementAndGet();
            entered.countDown();
            if (blocking && !released.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("blocked " + name + " was never released");
            }
            if (failure != null) {
                throw failure;
            }
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

        int calls() {
            return calls.get();
        }
    }
}
