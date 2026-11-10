package web;

import application.GlobalDataRefreshService;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import application.access.OwnerPolicy;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.access.AccountAccessHttp;
import web.access.OwnerAccess;
import web.task.BackgroundTaskService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STORY-STATUS-001: owner policy, protected global action/tasks, generic vs detailed status. */
class OwnerAdministrationTest {

    static final String OWNER = "aaaaaaaa-1111-2222-3333-444444444444";
    static final String OTHER = "bbbbbbbb-1111-2222-3333-444444444444";

    private AccountSessions sessions;
    private AccountAccessService access;
    private BackgroundTaskService tasks;
    private final AtomicInteger refreshRuns = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private GlobalDataRefreshService refresh;

    @BeforeEach
    void setUp() {
        sessions = new AccountSessions(Clock.systemUTC(), 8);
        access = new AccountAccessService(key -> { throw new InvalidCredentialException("no"); }, sessions);
        tasks = new BackgroundTaskService();
        refresh = new GlobalDataRefreshService() {
            @Override public RefreshResult refreshAll() throws Exception {
                refreshRuns.incrementAndGet();
                release.await(10, TimeUnit.SECONDS);
                return null;
            }
        };
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        tasks.close();
    }

    private MockMvc mvc(String configuredOwner) {
        var owner = new OwnerAccess(access, new OwnerPolicy(configuredOwner));
        var priceService = new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
            @Override public CacheStatus cacheStatus() { return new CacheStatus(5, 2, null); }
        });
        return MockMvcBuilders.standaloneSetup(new GlobalSyncApiController(refresh, tasks, owner),
                        new SyncTaskApiController(tasks, access, null, owner),
                        new SystemStatusApiController(refresh, priceService, access, (s, src) -> java.util.Map.of(), null, owner))
                .setControllerAdvice(new SyncApiExceptionHandler()).build();
    }

    private AccountSessions.Issued session(String guid) {
        return sessions.establish(new AccountProof(guid, Set.of(AccountGrant.ACCOUNT), Set.of(), null));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, AccountSessions.Issued s) {
        return b.cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, s.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext());
    }

    // ---- owner policy ----

    @Test
    void policyDefaultDeniesAndMatchesNormalizedGuidOnly() {
        var a = session(OWNER).context();
        assertFalse(new OwnerPolicy(null).isOwner(a));
        assertFalse(new OwnerPolicy("   ").isOwner(a));
        assertTrue(new OwnerPolicy(OWNER).isOwner(a));
        assertTrue(new OwnerPolicy("AAAAAAAA-1111-2222-3333-444444444444").isOwner(a));
        assertTrue(new OwnerPolicy(" " + OWNER.toUpperCase() + " ").isOwner(a));
        assertFalse(new OwnerPolicy(OWNER).isOwner(session(OTHER).context()));
        assertFalse(new OwnerPolicy(OWNER).isOwner(null));
    }

    @Test
    void malformedNonblankConfigurationFailsClearly() {
        for (String bad : new String[] {"not-a-guid", "12345", OWNER + "x", "aaaaaaaa-1-2-3-4",
                "+aaaaaa-1111-2222-3333-444444444444", "aaaaaaaa-1111-2222-3333-4444444444-4",
                "aaaaaaaa-111-12222-3333-444444444444", "-aaaaaaa-1111-2222-3333-44444444444"}) {
            var e = assertThrows(IllegalArgumentException.class, () -> new OwnerPolicy(bad), bad);
            assertTrue(e.getMessage().contains("gw2.owner.account-guid"));
        }
    }

    @Test
    void replacementKeyProofForTheSameGuidRemainsOwner() {
        var first = session(OWNER);
        var replacement = session(OWNER);
        assertNotEquals(first.sessionId(), replacement.sessionId());
        assertTrue(new OwnerPolicy(OWNER).isOwner(replacement.context()));
    }

    // ---- global action ----

    @Test
    void unsetOrBlankConfigurationDeniesEverybodyAndAdmitsNothing() throws Exception {
        var s = session(OWNER);
        for (String cfg : new String[] {null, ""}) {
            mvc(cfg).perform(as(post("/api/sync/global"), s)).andExpect(status().isForbidden());
            mvc(cfg).perform(as(get("/api/system/status/details"), s)).andExpect(status().isForbidden());
        }
        assertEquals(0, refreshRuns.get());
        assertTrue(tasks.find("anything").isEmpty());
    }

    @Test
    void rejectionsAre401Or403AndPerformNoWorkOrAdmission() throws Exception {
        var m = mvc(OWNER);
        m.perform(post("/api/sync/global")).andExpect(status().isUnauthorized());
        m.perform(post("/api/sync/global").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, "unknown")))
                .andExpect(status().isUnauthorized());
        m.perform(as(post("/api/sync/global"), session(OTHER))).andExpect(status().isForbidden());
        var owner = session(OWNER);
        m.perform(post("/api/sync/global").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, owner.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, "stale")).andExpect(status().isForbidden());
        // Client-supplied identity/role data never grants ownership.
        m.perform(as(post("/api/sync/global?accountId=" + OWNER + "&role=owner")
                .header("X-Role", "owner").header("X-Account-Id", OWNER), session(OTHER)))
                .andExpect(status().isForbidden());
        Thread.sleep(100);
        assertEquals(0, refreshRuns.get(), "no refresh work after rejected admission");
        // Nothing was admitted: an owner admission is not reported as a duplicate.
        m.perform(as(post("/api/sync/global"), owner)).andExpect(status().isAccepted());
    }

    @Test
    void ownerAdmitsDuplicateIsStill409AndTaskReadsAreOwnerOnlyInEveryState() throws Exception {
        var m = mvc(OWNER);
        var owner = session(OWNER);
        var other = session(OTHER);
        String body = m.perform(as(post("/api/sync/global"), owner)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
        assertTrue(tasks.isAdministrative(id));

        m.perform(as(post("/api/sync/global"), owner)).andExpect(status().isConflict());
        m.perform(as(post("/api/sync/global"), other)).andExpect(status().isForbidden());

        awaitState(id, "RUNNING");
        assertTaskReadable(m, id, owner, other);
        release.countDown();
        awaitState(id, "SUCCEEDED");
        assertTaskReadable(m, id, owner, other);
    }

    @Test
    void failedAndPendingOwnerTasksAreProtectedAndSchedulerTasksAreOwnerClassified() throws Exception {
        var m = mvc(OWNER);
        var owner = session(OWNER);
        String failed = tasks.submitAdministrative("GLOBAL_SYNC", () -> { throw new IllegalStateException("secret"); });
        awaitState(failed, "FAILED");
        assertTaskReadable(m, failed, owner, session(OTHER));
        assertFalse(m.perform(as(get("/api/sync/tasks/" + failed), owner)).andReturn().getResponse()
                .getContentAsString().contains("secret"));
        // The internal scheduler needs no browser session and its task is owner-classified too.
        var scheduler = new GlobalDataRefreshScheduler(refresh, tasks);
        scheduler.checkGlobalData();
        Thread.sleep(100);
        assertEquals(1, refreshRuns.get());
        // An ordinary task is not reclassified as owner work.
        String plain = tasks.submit("TP_REFRESH_PROFIT", () -> { });
        assertFalse(tasks.isAdministrative(plain));
        m.perform(get("/api/sync/tasks/" + plain)).andExpect(status().isOk());
    }

    private void assertTaskReadable(MockMvc m, String id, AccountSessions.Issued owner,
                                    AccountSessions.Issued other) throws Exception {
        m.perform(get("/api/sync/tasks/" + id)).andExpect(status().isUnauthorized());
        m.perform(as(get("/api/sync/tasks/" + id), other)).andExpect(status().isForbidden());
        m.perform(as(get("/api/sync/tasks/" + id), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"));
    }

    private void awaitState(String id, String state) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            if (tasks.find(id).map(t -> t.state().name().equals(state)).orElse(false)) return;
            Thread.sleep(10);
        }
        fail("task never reached " + state);
    }

    // ---- eviction race, PENDING and expiry ----

    /** Models the supervisor's race: a separate classification lookup reports the task already evicted. */
    @Test
    void evictionBetweenLookupAndClassificationNeverExposesAdministrativePayload() throws Exception {
        var racing = new BackgroundTaskService() {
            @Override public boolean isAdministrative(String taskId) { return false; }
        };
        try {
            String id = racing.submitAdministrative("GLOBAL_SYNC", () -> { });
            long end = System.nanoTime() + 10_000_000_000L;
            while (!racing.find(id).get().state().isTerminal() && System.nanoTime() < end) Thread.sleep(5);
            var owner = new OwnerAccess(access, new OwnerPolicy(OWNER));
            var m = MockMvcBuilders.standaloneSetup(new SyncTaskApiController(racing, access, null, owner))
                    .setControllerAdvice(new SyncApiExceptionHandler()).build();
            String anon = m.perform(get("/api/sync/tasks/" + id)).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(anon.contains("GLOBAL_SYNC") || anon.contains(id));
            m.perform(as(get("/api/sync/tasks/" + id), session(OTHER))).andExpect(status().isForbidden());
            m.perform(as(get("/api/sync/tasks/" + id), session(OWNER))).andExpect(status().isOk());
        } finally {
            racing.close();
        }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-08T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void pendingAdministrativeTaskIsOwnerOnlyAndBlocksDuplicateAdmission() throws Exception {
        var deferred = new java.util.ArrayList<Runnable>();
        var pendingTasks = new BackgroundTaskService(deferred::add);
        var owner = new OwnerAccess(access, new OwnerPolicy(OWNER));
        var m = MockMvcBuilders.standaloneSetup(new GlobalSyncApiController(refresh, pendingTasks, owner),
                        new SyncTaskApiController(pendingTasks, access, null, owner))
                .setControllerAdvice(new SyncApiExceptionHandler()).build();
        var ownerSession = session(OWNER);
        String body = m.perform(as(post("/api/sync/global"), ownerSession)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
        assertEquals("PENDING", pendingTasks.find(id).get().state().name(), "body has not been started");
        assertEquals(1, deferred.size());
        assertEquals(0, refreshRuns.get());
        m.perform(get("/api/sync/tasks/" + id)).andExpect(status().isUnauthorized());
        m.perform(as(get("/api/sync/tasks/" + id), session(OTHER))).andExpect(status().isForbidden());
        m.perform(as(get("/api/sync/tasks/" + id), ownerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"));
        m.perform(as(post("/api/sync/global"), ownerSession)).andExpect(status().isConflict());
        m.perform(as(post("/api/sync/global"), session(OTHER))).andExpect(status().isForbidden());
        assertEquals(1, deferred.size(), "no second admission");
    }

    @Test
    void expiredSessionIsRejectedForAdmissionTaskReadsAndDetailedStatus() throws Exception {
        var clock = new MutableClock();
        var expiring = new AccountSessions(clock, 8);
        var exAccess = new AccountAccessService(key -> { throw new InvalidCredentialException("no"); }, expiring);
        var owner = new OwnerAccess(exAccess, new OwnerPolicy(OWNER));
        var priceService = new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
            @Override public CacheStatus cacheStatus() { return new CacheStatus(5, 2, null); }
        });
        var m = MockMvcBuilders.standaloneSetup(new GlobalSyncApiController(refresh, tasks, owner),
                        new SyncTaskApiController(tasks, exAccess, null, owner),
                        new SystemStatusApiController(refresh, priceService, exAccess, (s, src) -> java.util.Map.of(), null, owner))
                .setControllerAdvice(new SyncApiExceptionHandler()).build();
        var s = expiring.establish(new AccountProof(OWNER, Set.of(AccountGrant.ACCOUNT), Set.of(), null));
        String id = tasks.submitAdministrative("GLOBAL_SYNC", () -> { });
        m.perform(as(get("/api/sync/tasks/" + id), s)).andExpect(status().isOk());
        m.perform(as(get("/api/system/status/details"), s)).andExpect(status().isOk());
        clock.now = clock.now.plus(AccountSessions.LEASE).plusSeconds(1);
        m.perform(as(get("/api/sync/tasks/" + id), s)).andExpect(status().isUnauthorized());
        m.perform(as(get("/api/system/status/details"), s)).andExpect(status().isUnauthorized());
        m.perform(as(post("/api/sync/global"), s)).andExpect(status().isUnauthorized());
        assertEquals(0, refreshRuns.get());
    }

    // ---- status ----

    @Test
    void genericStatusIsCoarseAndOmitsDetailedFieldsAndValues() throws Exception {
        String json = mvc(OWNER).perform(get("/api/system/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.globalHealth").value("NOT_RUN"))
                .andExpect(jsonPath("$.priceHealth").value("OK"))
                .andReturn().getResponse().getContentAsString();
        for (String forbidden : new String[] {"lastCheckedAt", "lastChangedAt", "lastRecipeSyncAt",
                "lastGraphRebuildAt", "lastFailure", "cachedPriceItems", "stalePriceItems",
                "newestPriceFetchedAt", "priceCacheError", "running", "taskId", "accountLastRefreshedAt",
                "null", "Exception"}) {
            assertFalse(json.contains(forbidden), forbidden + " leaked in " + json);
        }
    }

    @Test
    void detailedStatusIsOwnerOnly() throws Exception {
        var m = mvc(OWNER);
        m.perform(get("/api/system/status/details")).andExpect(status().isUnauthorized());
        String denied = m.perform(as(get("/api/system/status/details"), session(OTHER)))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertFalse(denied.contains("cachedPriceItems"));
        m.perform(as(get("/api/system/status/details"), session(OWNER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.cachedPriceItems").value(5))
                .andExpect(jsonPath("$.stalePriceItems").value(2));
    }

    @Test
    void nonOwnerStillReadsOwnAccountFreshnessOnGenericStatus() throws Exception {
        var guid = OTHER;
        var s = sessions.establish(new AccountProof(guid,
                Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES), Set.of(), null));
        var done = Instant.parse("2026-10-25T10:00:00Z");
        var priceService = new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
            @Override public CacheStatus cacheStatus() { return new CacheStatus(0, 0, null); }
        });
        var m = MockMvcBuilders.standaloneSetup(new SystemStatusApiController(refresh, priceService, access,
                (scope, sources) -> {
                    var out = new java.util.LinkedHashMap<String, repo.AccountSourceStateRepository.SourceState>();
                    for (String src : sources) out.put(src, new repo.AccountSourceStateRepository.SourceState(src, 1, done));
                    return out;
                }, null, new OwnerAccess(access, new OwnerPolicy(OWNER)))).build();
        m.perform(as(get("/api/system/status"), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLastRefreshedAt").exists())
                .andExpect(jsonPath("$.lastFailure").doesNotExist());
    }

    // ---- revised System Status aggregates ----

    @Test
    void authorizationPrecedesAggregatesAndGenericStatusNeverReadsThem() throws Exception {
        var reads = new AtomicInteger();
        var summary = new application.OwnerCatalogSummaryService(() -> null, new repo.SharedDataSummaryRepository() {
            @Override public Coverage items(java.sql.Connection c) { reads.incrementAndGet(); return new Coverage(3, 2, 1, null, null); }
            @Override public Coverage recipes(java.sql.Connection c) throws java.sql.SQLException { throw new java.sql.SQLException("down"); }
            @Override public Coverage tradingPostPrices(java.sql.Connection c) { reads.incrementAndGet(); return new Coverage(0, 0, 0, null, null); }
            @Override public AccountUsage accounts(java.sql.Connection c, Instant at) { reads.incrementAndGet(); return new AccountUsage(7, 2); }
        }, Clock.systemUTC());
        var priceService = new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
            @Override public CacheStatus cacheStatus() { return new CacheStatus(5, 2, null); }
        });
        var m = MockMvcBuilders.standaloneSetup(new SystemStatusApiController(refresh, priceService, access,
                (s, src) -> java.util.Map.of(), null, new OwnerAccess(access, new OwnerPolicy(OWNER)), summary)).build();

        m.perform(get("/api/system/status/details")).andExpect(status().isUnauthorized());
        m.perform(as(get("/api/system/status/details"), session(OTHER))).andExpect(status().isForbidden());
        m.perform(as(get("/api/system/status"), session(OWNER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").doesNotExist()).andExpect(jsonPath("$.items").doesNotExist());
        m.perform(get("/api/system/status")).andExpect(status().isOk());
        assertEquals(0, reads.get(), "denial and generic status run zero aggregate queries");

        String body = m.perform(as(get("/api/system/status/details"), session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.items.unknownTimestampRows").value(1))
                .andExpect(jsonPath("$.recipes.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.recipes.storedRows").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.accounts.totalAccounts").value(7))
                .andExpect(jsonPath("$.accounts.activeLast60Minutes").value(2))
                .andExpect(jsonPath("$.cachedPriceItems").value(5))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(OWNER) || body.contains(OTHER), "no account identifiers are disclosed");
    }
}
