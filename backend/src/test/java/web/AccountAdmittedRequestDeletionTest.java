package web;

import application.AccountDataDeletionService;
import application.ScopedAccountRefreshCoordinator;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
 * STORY-DELETE-001 admitted-request-first race over the real interceptor and request lifecycle (no
 * fixture answering before the handler). A's ordinary request is admitted and parked just before its
 * final account activity write; deletion must wait, B stays usable, the write finishes under the
 * admission, afterCompletion releases it, and only then does deletion commit. A later request on A's
 * old session is refused before any handler, so nothing can recreate the root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true"})
@AutoConfigureMockMvc
class AccountAdmittedRequestDeletionTest {
    private static final String ORIGIN = "http://localhost";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final CountDownLatch PARKED = new CountDownLatch(1);
    static final CountDownLatch PROCEED = new CountDownLatch(1);

    @RestController
    static class ParkedHandler {
        @GetMapping("/api/account/parked-write")
        ResponseEntity<String> parked(jakarta.servlet.http.HttpServletRequest request) throws Exception {
            if ("a".equals(request.getHeader("X-Test-Park"))) {
                PARKED.countDown();
                assertTrue(PROCEED.await(10, TimeUnit.SECONDS));
                EVENTS.add("final-activity-write");
            }
            return ResponseEntity.ok("ok");
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary
        AccountProofPort controlledProof() {
            return key -> new AccountProof(key.startsWith("a")
                    ? "11111111-1111-1111-1111-111111111111" : "22222222-2222-2222-2222-222222222222",
                    Set.of(AccountGrant.ACCOUNT), Set.of(), null);
        }

        @Bean @Primary
        AccountDataDeletionService recordingDeletion(AccountAccessService access,
                                                     ScopedAccountRefreshCoordinator coordinator) {
            return new AccountDataDeletionService(access, coordinator, scope -> EVENTS.add("root-deleted"));
        }

        @Bean
        ParkedHandler parkedHandler() { return new ParkedHandler(); }
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
    void deletionWaitsForTheAlreadyAdmittedRequestsFinalWriteAndThenNoOldRequestCanRunAgain() throws Exception {
        var a = prove("a-key");
        var b = prove("b-key");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var admitted = pool.submit(() -> http.perform(get("/api/account/parked-write").cookie(a.cookie())
                    .header("X-Account-Context", a.context()).header("X-Test-Park", "a")).andReturn().getResponse());
            assertTrue(PARKED.await(5, TimeUnit.SECONDS), "A's request is admitted and inside its handler");

            var deletion = pool.submit(() -> http.perform(delete("/api/account/data").header("Origin", ORIGIN)
                    .cookie(a.cookie()).header("X-Account-Context", a.context())).andReturn().getResponse());
            Thread.sleep(300);
            assertFalse(deletion.isDone(), "deletion waits for the admitted request");
            assertEquals(List.of(), EVENTS, "nothing was deleted or written yet");
            // B is independent and usable while A's deletion is pending
            assertEquals(200, http.perform(get("/api/account/parked-write").cookie(b.cookie())
                    .header("X-Account-Context", b.context())).andReturn().getResponse().getStatus());

            PROCEED.countDown();
            assertEquals(200, admitted.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(204, deletion.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(List.of("final-activity-write", "root-deleted"), EVENTS,
                    "the final write completed under the admission, before the root was deleted");
        } finally {
            pool.shutdownNow();
        }
        var late = http.perform(get("/api/account/parked-write").cookie(a.cookie())
                .header("X-Account-Context", a.context()).header("X-Test-Park", "a")).andReturn().getResponse();
        assertEquals(401, late.getStatus());
        assertTrue(late.getContentAsString().contains("ACCOUNT_DATA_DELETED"));
        assertEquals(List.of("final-activity-write", "root-deleted"), EVENTS, "no old request reached a handler");
    }
}
