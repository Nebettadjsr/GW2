package web;

import application.AccountRefreshService;
import application.BackgroundTasks;
import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.access.AccountAccessHttp;
import web.access.RequestAccountContext;
import web.task.BackgroundTaskService;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * HTTP contract for the keyed account-refresh triggers and the account-task status route
 * (STORY-API-011, SYNC-006). The real coordinator, task facility and access service are used; only
 * the upstream (key proof and the refresh body) are offline stand-ins, so nothing reaches GW2.
 */
class AccountSyncApiControllerTest {

    private static final String A = "11111111-2222-3333-4444-555555555555";
    private static final String B = "99999999-2222-3333-4444-555555555555";
    private static final Set<AccountGrant> ALL = Set.of(AccountGrant.values());
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Set<String> PARENT_ACCOUNTS = Set.of(A, B);
    private volatile boolean parentPublished = true;
    private final BackgroundTaskService tasks = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(Clock.systemUTC(), 8);
    private final Map<String, AccountProof> keys = new ConcurrentHashMap<>();
    private final List<String> runs = new CopyOnWriteArrayList<>();
    private volatile java.util.concurrent.CountDownLatch holdFirstRun;
    private final java.util.concurrent.atomic.AtomicInteger controlledOverrideInvocations =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger proofCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final AccountAccessService access = new AccountAccessService(key -> {
        proofCalls.incrementAndGet();
        AccountProof proof = keys.get(key);
        if (proof == null) throw new InvalidCredentialException("rejected");
        return proof;
    }, sessions);
    private final AccountRefreshService refresh = new AccountRefreshService() {
        @Override
        public void refreshSources(model.AccountScope scope, Set<String> sources,
                                   sync.AccountSync.AuthFetcher fetcher,
                                   AccountRefreshService.SourceOutcomes outcomes) throws Exception {
            runs.add(scope.id() + ":" + new java.util.TreeSet<>(sources));
            controlledOverrideInvocations.incrementAndGet();
            var hold = holdFirstRun;
            if (hold != null) {
                holdFirstRun = null;
                hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            // The controlled body publishes nothing and fails nothing: every requested source succeeded.
            for (String source : new java.util.TreeSet<>(sources)) outcomes.succeeded(source);
        }
    };
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            refresh, new BackgroundTaskServiceTasks(tasks), access, key -> url -> null);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AccountSyncApiController(coordinator, new AccountAccessHttp(""),
                            scope -> parentPublished && PARENT_ACCOUNTS.contains(scope.accountId().toString())),
                    new SyncTaskApiController(tasks, access, coordinator))
            .setControllerAdvice(new SyncApiExceptionHandler())
            .build();

    @AfterEach
    void close() {
        tasks.close();
    }

    private AccountSessions.Issued session(String account, String key) {
        AccountProof proof = new AccountProof(account, ALL, Set.of(), null);
        keys.put(key, proof);
        return sessions.establish(proof);
    }

    /** What the guard does for an authorized request: the published context plus the cookie. */
    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder,
                                                            AccountSessions.Issued s) {
        return builder.cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, s.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext())
                .requestAttr(RequestAccountContext.ATTRIBUTE, s.context());
    }

    private static MockHttpServletRequestBuilder trigger(String path, AccountSessions.Issued s, String key) {
        var builder = authorized(post(path), s).header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON);
        return key == null ? builder : builder.header(AccountAccessHttp.API_KEY_HEADER, key);
    }

    private void awaitRuns(int expected) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (runs.size() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, runs.size());
    }

    @Test
    void equipmentTabsTriggerDemandsOnlyTheOptionalSourceAndReusesOwnerScopedObservation() throws Exception {
        var s = session(A, "key-a");
        String body = mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, "key-a"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        awaitRuns(1);
        assertEquals(A + ":[CHARACTER_EQUIPMENT_TABS]", runs.get(0), "no ordinary source is demanded");
        String taskId = JsonPath.read(body, "$.taskId");
        mvc.perform(authorized(get("/api/sync/tasks/" + taskId), s)).andExpect(status().isOk());
        var other = session(B, "key-b");
        mvc.perform(authorized(get("/api/sync/tasks/" + taskId), other)).andExpect(status().isNotFound());
    }

    @Test
    void equipmentTabsTriggerProvesTheKeyExactlyOncePerRequestIncludingTheBoundedWait() throws Exception {
        var s = session(A, "key-a");
        proofCalls.set(0);
        mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, "key-a")).andExpect(status().isAccepted());
        assertEquals(1, proofCalls.get(), "one upstream proof round for the whole optional refresh");
        awaitRuns(1);
    }

    @Test
    void equipmentTabsTriggerWithoutPublishedParentAdmitsNothing() throws Exception {
        parentPublished = false;
        var s = session(A, "key-a");
        proofCalls.set(0);
        mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, "key-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PREREQUISITE_UNAVAILABLE"));
        assertEquals(1, proofCalls.get(), "missing parent still costs exactly one proof");
        Thread.sleep(150);
        assertTrue(runs.isEmpty(), "no task, no tab fetch");
    }

    @Test
    void equipmentTabsTriggerRequiresEachOfTheFourGrantsAKeyAndTheOwnOrigin() throws Exception {
        for (AccountGrant missing : List.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.INVENTORIES, AccountGrant.BUILDS)) {
            Set<AccountGrant> grants = new java.util.HashSet<>(ALL);
            grants.remove(missing);
            AccountProof proof = new AccountProof(A, grants, Set.of(), null);
            keys.put("k-" + missing, proof);
            var s = sessions.establish(proof);
            mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, "k-" + missing))
                    .andExpect(status().isForbidden());
        }
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, null)).andExpect(status().isBadRequest());
        mvc.perform(trigger("/api/sync/account/character-equipment-tabs", s, "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(authorized(post("/api/sync/account/character-equipment-tabs"), s).header("Origin", "http://evil.example")
                        .header(AccountAccessHttp.API_KEY_HEADER, "key-a").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        var bSession = session(B, "key-b");
        keys.put("key-a-as-b", keys.get("key-a"));
        mvc.perform(trigger("/api/sync/account/character-equipment-tabs", bSession, "key-a-as-b"))
                .andExpect(status().isForbidden());
        Thread.sleep(150);
        assertTrue(runs.isEmpty(), "nothing was admitted by any rejected request");
    }

    @Test
    void ordinarySourceSetsNeverIncludeEquipmentTabs() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account", s, "key-a")).andExpect(status().isAccepted());
        awaitRuns(1);
        assertFalse(runs.get(0).contains("EQUIPMENT"));
    }

    @Test
    void theControlledRefreshOverrideIsInvokedAndNoGatewayIsReached() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account/crafting-profit", s, "key-a")).andExpect(status().isAccepted());
        awaitRuns(1);
        assertEquals(1, controlledOverrideInvocations.get(),
                "the coordinator's refresh call reaches the controlled override, not the inherited gateway path");
    }

    @Test
    void aFullSyncBehindAProfitOnlyRunIsNotReportedAsThatRunsSuccessAndIsAdmittedAfterTheRecheck() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        holdFirstRun = release;
        var s = session(A, "key-a");
        String profitTask = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(trigger("/api/sync/account/crafting-profit", s, "key-a"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.admission").value("ADMITTED"))
                        .andExpect(jsonPath("$.coversRequest").value(true))
                        .andReturn().getResponse().getContentAsString(), "$.taskId");
        awaitRuns(1);

        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { /* test ends */ }
            release.countDown();
        }).start();
        String body = mvc.perform(trigger("/api/sync/account", s, "key-a"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.admission").value("ADMITTED"))
                .andExpect(jsonPath("$.coversRequest").value(true))
                .andReturn().getResponse().getContentAsString();

        assertNotEquals(profitTask, com.jayway.jsonpath.JsonPath.read(body, "$.taskId"),
                "the full sync has its own task; the narrower one is not advertised as it");
        awaitRuns(2);
        assertTrue(runs.get(1).contains("LUCK"), "Luck, uncovered by the Profit-only run, was admitted");
    }

    @Test
    void aKeyedFullRefreshIsAdmittedForTheSessionAccountOnly() throws Exception {
        var s = session(A, "key-a");
        String body = mvc.perform(trigger("/api/sync/account", s, "key-a"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext()))
                .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                .andReturn().getResponse().getContentAsString();
        awaitRuns(1);
        assertEquals(A + ":[ACCOUNT_RECIPES, BANK, CHARACTERS, LUCK, MATERIALS]", runs.get(0));
        assertFalse(body.contains("key-a"), "the response never carries the key");
    }

    /**
     * Restores the coverage dropped when the account trigger became scoped: GLOBAL_SYNC and the
     * account operation are different admission keys, so an unfinished global task never blocks a
     * keyed account refresh, and the two stay separately identifiable through the one status route.
     */
    @Test
    void anUnfinishedGlobalTaskDoesNotBlockTheKeyedAccountTrigger() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            String globalId = tasks.submit("GLOBAL_SYNC", () -> release.await());
            var s = session(A, "key-a");
            String body = mvc.perform(trigger("/api/sync/account", s, "key-a"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.operation").value("ACCOUNT_SYNC"))
                    .andReturn().getResponse().getContentAsString();
            awaitRuns(1);
            String accountId = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");
            org.junit.jupiter.api.Assertions.assertNotEquals(globalId, accountId);
            mvc.perform(authorized(get("/api/sync/tasks/" + globalId), s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operation").value("GLOBAL_SYNC"))
                    .andExpect(jsonPath("$.state").value("RUNNING"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void theProfitRefreshExcludesLuck() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account/crafting-profit", s, "key-a")).andExpect(status().isAccepted());
        awaitRuns(1);
        assertEquals(A + ":[ACCOUNT_RECIPES, BANK, CHARACTERS, MATERIALS]", runs.get(0));
    }

    @Test
    void theLuckRefreshRunsOnlyLuckAndNeedsOnlyLuckGrants() throws Exception {
        AccountProof luckOnly = new AccountProof(A,
                Set.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS), Set.of(), null);
        keys.put("key-luck", luckOnly);
        var s = sessions.establish(luckOnly);
        mvc.perform(trigger("/api/sync/account/luck", s, "key-luck"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.coversRequest").value(true));
        awaitRuns(1);
        assertEquals(A + ":[LUCK]", runs.get(0));
        // The same grants cannot start the Profit refresh: Luck-only recovery does not widen access.
        mvc.perform(trigger("/api/sync/account/crafting-profit", s, "key-luck")).andExpect(status().isForbidden());
    }

    @Test
    void theLuckRefreshRejectsAMissingKeyAndRequestFields() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account/luck", s, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CREDENTIAL_REQUIRED"));
        mvc.perform(trigger("/api/sync/account/luck", s, "key-a").content("{\"accountId\":\"x\"}"))
                .andExpect(status().is4xxClientError());
        assertTrue(runs.isEmpty());
    }

    @Test
    void aMissingKeyAdmitsNothingAndUsesNoConfiguredCredential() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account", s, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CREDENTIAL_REQUIRED"));
        Thread.sleep(100);
        assertTrue(runs.isEmpty());
        assertFalse(coordinator.isActive(model.AccountScope.of(A)));
    }

    @Test
    void anInvalidOrForeignKeyAdmitsNothing() throws Exception {
        var s = session(A, "key-a");
        keys.put("key-b", new AccountProof(B, ALL, Set.of(), null));
        mvc.perform(trigger("/api/sync/account", s, "unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIAL"));
        var s2 = session(A, "key-a2");
        mvc.perform(trigger("/api/sync/account", s2, "key-b")).andExpect(status().isForbidden());
        Thread.sleep(100);
        assertTrue(runs.isEmpty());
    }

    @Test
    void aRequestWithoutASameOriginHeaderIsRejectedBeforeAnyWork() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(authorized(post("/api/sync/account"), s).header(AccountAccessHttp.API_KEY_HEADER, "key-a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ORIGIN_REJECTED"));
        Thread.sleep(100);
        assertTrue(runs.isEmpty());
    }

    @Test
    void anUnexpectedBodyFieldIsRejectedBeforeAdmission() throws Exception {
        var s = session(A, "key-a");
        mvc.perform(trigger("/api/sync/account", s, "key-a").content("{\"accountId\":\"" + B + "\"}"))
                .andExpect(status().isBadRequest());
        Thread.sleep(100);
        assertTrue(runs.isEmpty());
    }

    @Test
    void accountTasksAreObservableOnlyByTheOwningAccount() throws Exception {
        var a = session(A, "key-a");
        var b = session(B, "key-b");
        String body = mvc.perform(trigger("/api/sync/account", a, "key-a"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitRuns(1);
        String path = JsonPath.read(body, "$.statusUrl");
        String unknown = "/api/sync/tasks/00000000-0000-0000-0000-000000000000";

        mvc.perform(authorized(get(path), a)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        // Foreign and unknown identifiers are indistinguishable once authenticated.
        String foreign = mvc.perform(authorized(get(path), b)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(authorized(get(unknown), b)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(missing, foreign);
        // A replacement session for the same GUID may observe retained work.
        var replacement = session(A, "key-a-replacement");
        mvc.perform(authorized(get(path), replacement)).andExpect(status().isOk());
        // No session at all is 401, never the task.
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        // Expired/revoked session: no status access.
        access.logout(a.sessionId());
        mvc.perform(authorized(get(path), a)).andExpect(status().isUnauthorized());
    }
}
