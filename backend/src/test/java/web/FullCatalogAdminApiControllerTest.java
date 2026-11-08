package web;

import application.FullCatalogAdministrationService;
import application.CraftingGraphRebuildService;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import application.access.OwnerPolicy;
import api.TaskDeadline;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sync.FullCatalogGateway;
import web.access.AccountAccessHttp;
import web.access.OwnerAccess;
import web.task.BackgroundTaskService;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STORY-STATUS-004: owner-only admission, stable names, exclusion, graph and bound behavior. */
class FullCatalogAdminApiControllerTest {
    static final String OWNER = "aaaaaaaa-1111-2222-3333-444444444444";
    static final String OTHER = "bbbbbbbb-1111-2222-3333-444444444444";

    private AccountSessions sessions;
    private AccountAccessService access;
    private BackgroundTaskService tasks;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicInteger itemRuns = new AtomicInteger();
    private final AtomicInteger recipeRuns = new AtomicInteger();
    private final AtomicInteger tpRuns = new AtomicInteger();
    private final AtomicInteger invalidations = new AtomicInteger();
    private final AtomicInteger rebuilds = new AtomicInteger();
    private volatile boolean failRebuild;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions = new AccountSessions(Clock.systemUTC(), 8);
        access = new AccountAccessService(key -> { throw new InvalidCredentialException("no"); }, sessions);
        tasks = new BackgroundTaskService();
        var gateway = new FullCatalogGateway() {
            @Override public void syncAllItems(TaskDeadline d) throws Exception { itemRuns.incrementAndGet(); started.countDown(); release.await(10, TimeUnit.SECONDS); }
            @Override public void syncAllRecipes(TaskDeadline d) throws Exception { recipeRuns.incrementAndGet(); started.countDown(); release.await(10, TimeUnit.SECONDS); }
            @Override public void syncAllTradingPost(TaskDeadline d) throws Exception { tpRuns.incrementAndGet(); release.await(10, TimeUnit.SECONDS); }
        };
        var graph = new CraftingGraphRebuildService(null) {
            @Override public void invalidate() { invalidations.incrementAndGet(); }
            @Override public void rebuild(TaskDeadline dl) { rebuilds.incrementAndGet(); if (failRebuild) throw new IllegalStateException("rebuild"); }
        };
        var service = new FullCatalogAdministrationService(gateway, graph, TaskDeadline::start);
        var owner = new OwnerAccess(access, new OwnerPolicy(OWNER));
        mvc = MockMvcBuilders.standaloneSetup(new FullCatalogAdminApiController(service, tasks, owner),
                        new SyncTaskApiController(tasks, access, null, owner))
                .setControllerAdvice(new SyncApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() { release.countDown(); tasks.close(); }

    private AccountSessions.Issued session(String guid) {
        return sessions.establish(new AccountProof(guid, Set.of(AccountGrant.ACCOUNT), Set.of(), null));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, AccountSessions.Issued s) {
        return b.cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, s.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext());
    }

    @Test
    void anonymousAndNonOwnerAdmitNothing() throws Exception {
        for (String path : new String[] {"/items", "/recipes", "/trading-post"}) {
            mvc.perform(post("/api/sync/full" + path)).andExpect(status().isUnauthorized());
            mvc.perform(as(post("/api/sync/full" + path), session(OTHER))).andExpect(status().isForbidden());
        }
        assertEquals(0, itemRuns.get() + recipeRuns.get() + tpRuns.get());
    }

    @Test
    void ownerGetsStableOperationNamesAndRejectsRequestFields() throws Exception {
        var s = session(OWNER);
        mvc.perform(as(post("/api/sync/full/trading-post"), s)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("FULL_TP_CATALOG"));
        mvc.perform(as(post("/api/sync/full/items").contentType("application/json").content("{\"x\":1}"), s))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/sync/full/items"), s)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("FULL_ITEM_CATALOG"));
    }

    @Test
    void itemsAndRecipesShareGlobalSyncExclusion_sameOperationDeduplicates_tpAndAccountIndependent() throws Exception {
        var s = session(OWNER);
        String body = mvc.perform(as(post("/api/sync/full/items"), s)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        mvc.perform(as(post("/api/sync/full/items"), s)).andExpect(status().isConflict());
        mvc.perform(as(post("/api/sync/full/recipes"), s)).andExpect(status().isConflict());
        assertEquals(0, recipeRuns.get());
        assertEquals(1, itemRuns.get());
        // The existing global sync action shares the same key.
        assertThrows(web.task.TaskAlreadyRunningException.class, () -> tasks.submitAdministrative("GLOBAL_SYNC", () -> { }));
        // Full TP and account tasks are independent of the catalog mutation.
        mvc.perform(as(post("/api/sync/full/trading-post"), s)).andExpect(status().isAccepted());
        tasks.submit("ACCOUNT_SYNC", "ACCOUNT_SYNC:acct", () -> { });
        mvc.perform(as(get("/api/sync/tasks/" + id), s)).andExpect(status().isOk());
        mvc.perform(as(get("/api/sync/tasks/" + id), session(OTHER))).andExpect(status().isForbidden());
    }

    @Test
    void exclusionStaysHeldUntilTheBodyActuallyExits() throws Exception {
        var s = session(OWNER);
        String body = mvc.perform(as(post("/api/sync/full/recipes"), s)).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals("RUNNING", tasks.find(id).get().state().name());
        mvc.perform(as(post("/api/sync/full/items"), s)).andExpect(status().isConflict());
        release.countDown();
        for (int i = 0; i < 100 && !tasks.find(id).get().state().isTerminal(); i++) Thread.sleep(20);
        assertEquals("SUCCEEDED", tasks.find(id).get().state().name());
        assertEquals(1, invalidations.get());
        assertEquals(1, rebuilds.get());
        mvc.perform(as(post("/api/sync/full/items"), s)).andExpect(status().isAccepted());
    }

    @Test
    void failedRebuildFailsTheTaskAndLeavesGraphInvalidated() throws Exception {
        failRebuild = true;
        release.countDown();
        var s = session(OWNER);
        String body = mvc.perform(as(post("/api/sync/full/recipes"), s)).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
        for (int i = 0; i < 100 && !tasks.find(id).get().state().isTerminal(); i++) Thread.sleep(20);
        assertEquals("FAILED", tasks.find(id).get().state().name());
        assertEquals(2, invalidations.get(), "invalidated before rebuild and again after the failure; never re-populated");
    }

    @Test
    void exhaustedBoundBeforeGraphPhaseFailsWithoutRebuild() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var gateway = new FullCatalogGateway() {
            @Override public void syncAllRecipes(TaskDeadline d) { clock.addAndGet(java.time.Duration.ofMinutes(31).toNanos()); }
        };
        var graph = new CraftingGraphRebuildService(null) {
            @Override public void invalidate() { invalidations.incrementAndGet(); }
            @Override public void rebuild(TaskDeadline dl) { rebuilds.incrementAndGet(); }
        };
        var service = new FullCatalogAdministrationService(gateway, graph,
                () -> TaskDeadline.after(TaskDeadline.TASK_BOUND, clock::get));
        assertThrows(TaskDeadline.ExceededException.class, service::syncRecipes);
        assertEquals(0, rebuilds.get());
        assertEquals(1, invalidations.get(), "stale relationships are not served after an aborted publication");
    }

    @Test
    void expiryDuringRebuildFailsAndLeavesGraphInvalidated_forRecipesAndItems() throws Exception {
        for (boolean recipes : new boolean[] {true, false}) {
            invalidations.set(0);
            rebuilds.set(0);
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var graph = new CraftingGraphRebuildService(null) {
                @Override public void invalidate() { invalidations.incrementAndGet(); }
                @Override public void rebuild(TaskDeadline dl) {
                    rebuilds.incrementAndGet();
                    clock.addAndGet(java.time.Duration.ofMinutes(31).toNanos()); // the rebuild overran the bound
                }
            };
            var service = new FullCatalogAdministrationService(new FullCatalogGateway() {
                @Override public void syncAllRecipes(TaskDeadline d) { }
                @Override public void syncAllItems(TaskDeadline d) { }
            }, graph, () -> TaskDeadline.after(TaskDeadline.TASK_BOUND, clock::get));
            assertThrows(TaskDeadline.ExceededException.class, recipes ? service::syncRecipes : service::syncItems);
            assertEquals(1, rebuilds.get());
            assertEquals(2, invalidations.get(), "overrun rebuild result is discarded: graph invalidated, task FAILED");
        }
    }

    @Test
    void failedItemRebuildFailsAndLeavesGraphInvalidated() throws Exception {
        var graph = new CraftingGraphRebuildService(null) {
            @Override public void invalidate() { invalidations.incrementAndGet(); }
            @Override public void rebuild(TaskDeadline dl) { throw new IllegalStateException("rebuild"); }
        };
        var service = new FullCatalogAdministrationService(new FullCatalogGateway() {
            @Override public void syncAllItems(TaskDeadline d) { }
        }, graph, TaskDeadline::start);
        assertThrows(IllegalStateException.class, service::syncItems);
        assertEquals(2, invalidations.get());
    }
}
