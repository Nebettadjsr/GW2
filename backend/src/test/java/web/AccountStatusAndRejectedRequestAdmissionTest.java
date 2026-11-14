package web;

import application.AccountDataDeletionService;
import application.ScopedAccountRefreshCoordinator;
import application.ScopedSourceGate;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountProofPort;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * STORY-DELETE-001: GET /api/system/status (outside the lifecycle interceptor) holds a shared admission
 * through its final activity write, and a request rejected inside preHandle after admission releases it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true"})
@AutoConfigureMockMvc
class AccountStatusAndRejectedRequestAdmissionTest {
    private static final String ORIGIN = "http://localhost";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final CountDownLatch PARKED = new CountDownLatch(1);
    static final CountDownLatch PROCEED = new CountDownLatch(1);
    static final String A = "11111111-1111-1111-1111-111111111111";

    @TestConfiguration
    static class Config {
        @Bean @Primary
        AccountProofPort controlledProof() {
            return key -> new AccountProof(key.startsWith("a") ? A : "22222222-2222-2222-2222-222222222222",
                    Set.of(AccountGrant.ACCOUNT), Set.of(), null);
        }

        @Bean @Primary
        AccountDataDeletionService recordingDeletion(AccountAccessService access,
                                                     ScopedAccountRefreshCoordinator coordinator) {
            return new AccountDataDeletionService(access, coordinator, scope -> EVENTS.add("root-deleted"));
        }

        @Bean @Primary
        SystemStatusApiController.AccountStateReader stateReader() {
            return (scope, sources) -> {
                EVENTS.add("account-data-read:" + scope.accountId());
                return Map.of();
            };
        }

        @Bean @Primary
        ScopedSourceGate parkingGate() {
            return new ScopedSourceGate((scope, source) -> java.util.Optional.empty(), (scope, at) -> {
                EVENTS.add("activity:" + scope.accountId());
                if (scope.accountId().toString().equals(A) && PARKED.getCount() == 1) {
                    PARKED.countDown();
                    try {
                        assertTrue(PROCEED.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    EVENTS.add("final-activity-write");
                }
            }, Clock.systemUTC(), Duration.ofMinutes(15));
        }
    }

    @Autowired MockMvc http;

    private record Proof(Cookie cookie, String context) {}

    private Proof prove(String key) throws Exception {
        var response = http.perform(post("/api/session").header("Origin", ORIGIN).header("X-GW2-API-Key", key))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        String setCookie = response.getHeader("Set-Cookie");
        return new Proof(new Cookie("GW2_SESSION", setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'))),
                com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessContext"));
    }

    @Test
    void statusDrainsItsActivityWriteBeforeDeletionAndAWaitingStatusNeverTouchesData() throws Exception {
        var a = prove("a-key");
        var pool = Executors.newFixedThreadPool(3);
        try {
            var admitted = pool.submit(() -> http.perform(get("/api/system/status").cookie(a.cookie())
                    .header("X-Account-Context", a.context())).andReturn().getResponse());
            assertTrue(PARKED.await(5, TimeUnit.SECONDS), "status is admitted and at its activity write");
            var deletion = pool.submit(() -> http.perform(delete("/api/account/data").header("Origin", ORIGIN)
                    .cookie(a.cookie()).header("X-Account-Context", a.context())).andReturn().getResponse());
            Thread.sleep(300);
            assertFalse(deletion.isDone(), "deletion waits for the admitted status request");
            var waiting = pool.submit(() -> http.perform(get("/api/system/status").cookie(a.cookie())
                    .header("X-Account-Context", a.context())).andReturn().getResponse());
            Thread.sleep(300);
            assertFalse(waiting.isDone(), "a status request behind the deletion waits");

            PROCEED.countDown();
            assertEquals(200, admitted.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(204, deletion.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(401, waiting.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(List.of("account-data-read:" + A, "activity:" + A, "final-activity-write", "root-deleted"),
                    EVENTS, "the waiting status request reached neither account data nor activity");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRequestRejectedInPreHandleAfterAdmissionDoesNotLeakItsAdmission() throws Exception {
        var b = prove("b-key");
        for (int i = 0; i < 2; i++) {
            try {
                var response = http.perform(get("/api/account/bank?accountId=%ZZ").cookie(b.cookie())
                        .header("X-Account-Context", b.context())).andReturn().getResponse();
                assertTrue(response.getStatus() >= 400);
            } catch (Exception rejected) {
                // the decoding failure may surface from MockMvc itself; the admission must still be gone
            }
        }
        var deletion = Executors.newSingleThreadExecutor().submit(() -> http.perform(delete("/api/account/data")
                .header("Origin", ORIGIN).cookie(b.cookie()).header("X-Account-Context", b.context()))
                .andReturn().getResponse());
        assertEquals(204, deletion.get(5, TimeUnit.SECONDS).getStatus(), "no admission leaked");
    }
}
