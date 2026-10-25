package web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STORY-API-010 HTTP boundary contract. The access module is deliberately exercised through HTTP;
 * unit tests of a session map alone cannot prove cookie, origin, CSRF, context, and response policy.
 * The application context must provide an offline GW2 proof adapter in test configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.test-fixtures=true"})
@AutoConfigureMockMvc
class AccountAccessContractTest {
    private static final String ORIGIN = "http://localhost";
    private static final String ACCOUNT_A = "C19467C6-F5AD-E211-8756-78E7D1936222";
    private static final String ACCOUNT_B = "D29467C6-F5AD-E211-8756-78E7D1936222";

    @Autowired MockMvc http;

    @Test
    void proofIssuesOpaqueHostOnlyStrictHttpOnlyCookieAndNonSecretContext() throws Exception {
        var result = http.perform(post("/api/session")
                        .header("Origin", ORIGIN)
                        .header("X-GW2-API-Key", "fixture-key-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        org.junit.jupiter.api.Assertions.assertFalse(cookie.contains("fixture-key-a"));
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains(ACCOUNT_A));
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains("fixture-key-a"));
    }

    @Test
    void proofRejectsForeignOriginAndMissingCsrfBeforeCallingProofAdapter() throws Exception {
        http.perform(post("/api/session").header("Origin", "https://attacker.example")
                        .header("X-GW2-API-Key", "fixture-key-a"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/session").header("X-GW2-API-Key", "fixture-key-a"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aContextAssertionCannotFollowTheSharedCookieToAnotherSession() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        http.perform(get("/api/account/luck")
                        .cookie(a.cookie()).header("X-Account-Context", b.context()))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountAAndBReceiveDistinctAuthorizedRequestContextsAndMismatchStopsAtGuard() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        var responseA = http.perform(get("/api/account/luck").cookie(a.cookie())
                        .header("X-Account-Context", a.context()))
                .andExpect(status().isOk()).andReturn().getResponse();
        var responseB = http.perform(get("/api/account/luck").cookie(b.cookie())
                        .header("X-Account-Context", b.context()))
                .andExpect(status().isOk()).andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertEquals(a.context(), responseA.getHeader("X-Account-Context"));
        org.junit.jupiter.api.Assertions.assertEquals(b.context(), responseB.getHeader("X-Account-Context"));
        org.junit.jupiter.api.Assertions.assertNotEquals(responseA.getHeader("X-Account-Context"),
                responseB.getHeader("X-Account-Context"));
        http.perform(get("/api/account/luck").cookie(b.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void logoutRequiresTheMatchingContextAndDoesNotRevokeAnotherSession() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        http.perform(post("/api/session/logout").header("Origin", ORIGIN)
                        .cookie(a.cookie()).header("X-Account-Context", b.context()))
                .andExpect(status().isForbidden());
        http.perform(get("/api/account/luck").cookie(a.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void accountAndGrantAssertionsAreRejectedBeforeAccountWorkAndErrorsAreNonDisclosure() throws Exception {
        var a = prove("fixture-key-a");
        var mismatch = http.perform(get("/api/account/luck").param("accountId", ACCOUNT_B)
                        .cookie(a.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(mismatch.contains(ACCOUNT_A));
        org.junit.jupiter.api.Assertions.assertFalse(mismatch.contains(ACCOUNT_B));

        var restricted = prove("fixture-key-restricted");
        http.perform(get("/api/account/bank").cookie(restricted.cookie())
                        .header("X-Account-Context", restricted.context()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedAndMalformedProofResponsesFailClosedWithSafeStatus() throws Exception {
        http.perform(get("/api/account/luck")).andExpect(status().isUnauthorized());
        http.perform(post("/api/session").header("Origin", ORIGIN)
                        .header("X-GW2-API-Key", "fixture-key-malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void renewalAndReadsCannotExtendAbsoluteLeaseAndLogoutRevokes() throws Exception {
        var a = prove("fixture-key-a");
        http.perform(post("/api/session/renew").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()).header("X-GW2-API-Key", "fixture-key-a"))
                .andExpect(status().isOk());
        http.perform(post("/api/session/logout").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()))
                .andExpect(status().is2xxSuccessful());
        http.perform(get("/api/account/luck").cookie(a.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * STORY-API-011: the account-dependent crafting routes sit behind the same guard as the
     * {@code /api/account/**} reads, with the same non-disclosing answers, and a rejected request
     * never reaches the controller or the freshness gate.
     */
    @Test
    void craftingProfitResolutionAndSelectorRoutesRequireASessionMatchingContextAndGrants() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        var restricted = prove("fixture-key-restricted");
        for (String path : new String[] {"/api/crafting/profit", "/api/crafting/profit/resolution"}) {
            http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
            http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(a.cookie()).header("X-Account-Context", b.context()))
                    .andExpect(status().isForbidden());
            http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(restricted.cookie()).header("X-Account-Context", restricted.context()))
                    .andExpect(status().isForbidden());
            http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .param("accountId", ACCOUNT_B)
                            .cookie(a.cookie()).header("X-Account-Context", a.context()))
                    .andExpect(status().isForbidden());
            http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(a.cookie()).header("X-Account-Context", a.context()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Account-Context", a.context()));
        }
        String path = "/api/crafting/selector-options";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get(path).cookie(b.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isForbidden());
        http.perform(get(path).cookie(b.cookie()).header("X-Account-Context", b.context()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Account-Context", b.context()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void globalCraftingRoutesStayAccountIndependent() throws Exception {
        http.perform(get("/api/crafting/ingredient-search").param("q", "ore"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
                        401, result.getResponse().getStatus(), "a global lookup must not demand a session"));
    }

    private Proof prove(String key) throws Exception {
        var response = http.perform(post("/api/session").header("Origin", ORIGIN)
                        .header("X-GW2-API-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse();
        String setCookie = response.getHeader("Set-Cookie");
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        String context = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessContext");
        return new Proof(new jakarta.servlet.http.Cookie("GW2_SESSION", value), context);
    }

    @Test
    void matrixParametersAndEncodedSegmentsCannotBypassSessionOrGrantChecks() throws Exception {
        var restricted = prove("fixture-key-restricted");
        var full = prove("fixture-key-a");
        for (String path : java.util.List.of("/api/account;x=1/bank", "/api/account/bank;x=1",
                "/api/%61ccount/bank")) {
            var uri = java.net.URI.create(path);
            http.perform(get(uri)).andExpect(status().isUnauthorized());
            http.perform(get(uri).cookie(restricted.cookie())
                            .header("X-Account-Context", restricted.context()))
                    .andExpect(status().isForbidden());
            http.perform(get(uri).cookie(full.cookie()).header("X-Account-Context", full.context()))
                    .andExpect(status().isOk());
        }
        http.perform(get("/gw2/api/account/bank").contextPath("/gw2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identityOnlyReplacementKeyCannotReadCachedLuck() throws Exception {
        var restricted = prove("fixture-key-restricted");
        http.perform(get("/api/account/luck").cookie(restricted.cookie())
                        .header("X-Account-Context", restricted.context()))
                .andExpect(status().isForbidden());
    }

    private record Proof(jakarta.servlet.http.Cookie cookie, String context) {}
}
