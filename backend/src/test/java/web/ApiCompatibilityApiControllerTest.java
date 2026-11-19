package web;

import api.ApiCompatibilityChecker;
import api.ApiCompatibilityResult;
import api.PublicApiTransport;
import application.ApiCompatibilityService;
import application.access.*;
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

/** STORY-OPS-008: owner-first authorization, coalescing, cache-only reads and no leakage. */
class ApiCompatibilityApiControllerTest {
    static final String OWNER = "aaaaaaaa-1111-2222-3333-444444444444";
    static final String OTHER = "bbbbbbbb-1111-2222-3333-444444444444";
    static final String BASE = "/api/system/api-compatibility";

    private AccountSessions sessions;
    private BackgroundTaskService tasks;
    private final AtomicInteger upstream = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions = new AccountSessions(Clock.systemUTC(), 8);
        var access = new AccountAccessService(key -> { throw new InvalidCredentialException("no"); }, sessions);
        tasks = new BackgroundTaskService();
        PublicApiTransport blocked = url -> {
            upstream.incrementAndGet();
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new PublicApiTransport.Response(500, "SECRET-UPSTREAM-BODY");
        };
        var service = new ApiCompatibilityService(tasks,
                () -> new ApiCompatibilityChecker(blocked, Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC), System::nanoTime),
                new Clock() {
                    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                    @Override public Clock withZone(java.time.ZoneId z) { return this; }
                    @Override public Instant instant() { return clock.instant(); }
                });
        var owner = new OwnerAccess(access, new OwnerPolicy(OWNER));
        mvc = MockMvcBuilders.standaloneSetup(new ApiCompatibilityApiController(service, owner, new AccountAccessHttp("")))
                .setControllerAdvice(new SyncApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() { release.countDown(); tasks.close(); }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String guid) {
        var s = sessions.establish(new AccountProof(guid, Set.of(AccountGrant.ACCOUNT), Set.of(), null));
        return b.cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, s.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext());
    }

    private MockHttpServletRequestBuilder ownerPost() {
        return as(post(BASE + "/check"), OWNER).header("Origin", "http://localhost");
    }

    @Test
    void anonymousAndNonOwnerGetNothingAndTriggerNoUpstreamWork() throws Exception {
        mvc.perform(post(BASE + "/check")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(as(post(BASE + "/check"), OTHER).header("Origin", "http://localhost")).andExpect(status().isForbidden());
        mvc.perform(as(get(BASE), OTHER)).andExpect(status().isForbidden());
        Thread.sleep(100);
        assertEquals(0, upstream.get());
    }

    @Test
    void statusReadsAreCacheOnlyAndNeverCallUpstream_notCheckedIsTruthful() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(as(get(BASE), OWNER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.state").value("NOT_CHECKED"))
                    .andExpect(jsonPath("$.stale").value(false));
        }
        assertEquals(0, upstream.get());
    }

    @Test
    void foreignOrMissingOriginIsRejectedBeforeAdmission() throws Exception {
        mvc.perform(as(post(BASE + "/check"), OWNER)).andExpect(status().isForbidden());
        mvc.perform(as(post(BASE + "/check"), OWNER).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ORIGIN_REJECTED"));
        assertEquals(0, upstream.get());
    }

    @Test
    void duplicateInFlightCheckIsRejectedAndRunningThenUnavailableResultNeverLooksSuccessful() throws Exception {
        mvc.perform(ownerPost()).andExpect(status().isAccepted()).andExpect(jsonPath("$.result.state").value("RUNNING"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        mvc.perform(ownerPost()).andExpect(status().isConflict());
        mvc.perform(as(get(BASE), OWNER)).andExpect(jsonPath("$.result.state").value("RUNNING"));
        int during = upstream.get();
        mvc.perform(as(get(BASE), OWNER)).andExpect(status().isOk());
        assertEquals(during, upstream.get());
        release.countDown();
        String body = null;
        for (int i = 0; i < 100; i++) {
            body = mvc.perform(as(get(BASE), OWNER)).andReturn().getResponse().getContentAsString();
            if (body.contains("\"COMPLETE\"")) break;
            Thread.sleep(50);
        }
        assertTrue(body.contains("\"COMPLETE\""), body);
        assertTrue(body.contains("\"overall\":\"UNAVAILABLE\""), body);
        assertFalse(body.contains("SECRET-UPSTREAM-BODY"));
        assertEquals(7, upstream.get());
        // A finished check can be repeated.
        mvc.perform(ownerPost()).andExpect(status().isAccepted());
    }

    @Test
    void completedResultBecomesStaleAfterTwentyFourHours() throws Exception {
        release.countDown();
        mvc.perform(ownerPost()).andExpect(status().isAccepted());
        for (int i = 0; i < 100 && !mvc.perform(as(get(BASE), OWNER)).andReturn().getResponse().getContentAsString().contains("COMPLETE"); i++) Thread.sleep(50);
        mvc.perform(as(get(BASE), OWNER)).andExpect(jsonPath("$.stale").value(false));
        clock = Clock.fixed(Instant.parse("2026-11-20T10:00:01Z"), ZoneOffset.UTC);
        mvc.perform(as(get(BASE), OWNER)).andExpect(jsonPath("$.stale").value(true));
        assertEquals(ApiCompatibilityResult.State.COMPLETE, ApiCompatibilityResult.State.valueOf("COMPLETE"));
    }
}
