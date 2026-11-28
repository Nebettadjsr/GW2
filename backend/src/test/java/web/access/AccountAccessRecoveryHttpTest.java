package web.access;

import domain.access.AccountGrant;
import domain.access.AccountProof;
import domain.access.InvalidCredentialException;
import domain.access.MalformedProofException;
import domain.access.ProofUnavailableException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static web.access.AccountAccessHttpFixture.*;

/** #111: public session HTTP outcomes, absolute leases and recovery without upstream or database. */
class AccountAccessRecoveryHttpTest {
    private AccountAccessHttpFixture f;

    @BeforeEach void open() { f = new AccountAccessHttpFixture(); }
    @AfterEach void close() { f.close(); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://foreign.example"})
    void proofRequiresTheServedOriginBeforeCallingTheProofPort(String origin) throws Exception {
        var request = post("/api/session").header("X-GW2-API-Key", KEY);
        if (origin != null) request.header("Origin", origin);
        refused(f.http.perform(request), 403, "ORIGIN_REJECTED");
        assertEquals(0, f.state.proofCalls);
        assertEquals(0, f.state.sessions.size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankOrMissingProofKeyIsNotAnUpstreamAttempt(String key) throws Exception {
        var request = post("/api/session").header("Origin", "http://localhost");
        if (key != null) request.header("X-GW2-API-Key", key);
        refused(f.http.perform(request), 400, "CREDENTIAL_REQUIRED");
        assertEquals(0, f.state.proofCalls);
        assertEquals(0, f.state.sessions.size());
    }

    @ParameterizedTest
    @CsvSource({"invalid,401,INVALID_CREDENTIAL,0", "malformed,401,INVALID_CREDENTIAL,0",
            "unavailable,503,PROOF_UNAVAILABLE,30", "rate-limited,503,PROOF_UNAVAILABLE,60"})
    void failedProofHasTheExactSafeStatusAndRetryGuidance(String failure, int status, String code,
                                                         int retrySeconds) throws Exception {
        failProof(failure);
        var response = refused(f.http.perform(post("/api/session").header("Origin", "http://localhost")
                .header("X-GW2-API-Key", KEY)), status, code);
        assertEquals(retrySeconds == 0 ? null : Integer.toString(retrySeconds), response.getHeader("Retry-After"));
        assertEquals(1, f.state.proofCalls);
        assertEquals(0, f.state.sessions.size(), "a failed proof installs no session");
    }

    @ParameterizedTest
    @CsvSource({"missing-cookie,401,SESSION_REQUIRED", "unknown-cookie,401,SESSION_REQUIRED",
            "expired,401,SESSION_REQUIRED", "missing-key,400,CREDENTIAL_REQUIRED",
            "missing-context,403,ACCESS_CONTEXT_MISMATCH", "blank-context,403,ACCESS_CONTEXT_MISMATCH",
            "wrong-context,403,ACCESS_CONTEXT_MISMATCH",
            "deleted,401,ACCOUNT_DATA_DELETED"})
    void renewalRefusesAnUnusableLeaseBeforeReproving(String condition, int status, String code) throws Exception {
        Proof live = f.prove();
        Cookie cookie = live.cookie();
        String context = live.accessContext();
        if (condition.equals("unknown-cookie")) cookie = new Cookie("GW2_SESSION", "unknown-session");
        if (condition.equals("expired")) f.state.clock.now = START.plusSeconds(900);
        if (condition.equals("blank-context")) context = " ";
        if (condition.equals("wrong-context")) context = "another-tabs-context";
        if (condition.equals("deleted")) f.state.access.invalidateAccountSessions(A);
        var request = post("/api/session/renew").header("Origin", "http://localhost");
        if (!condition.equals("missing-context")) request.header("X-Account-Context", context);
        if (!condition.equals("missing-cookie")) request.cookie(cookie);
        if (!condition.equals("missing-key")) request.header("X-GW2-API-Key", KEY);
        refused(f.http.perform(request), status, code);
        assertEquals(1, f.state.proofCalls, "only the original successful proof reached upstream");
        assertEquals(0, f.state.handlerCalls);
    }

    @ParameterizedTest
    @CsvSource({"invalid,401,INVALID_CREDENTIAL,0", "malformed,401,INVALID_CREDENTIAL,0",
            "unavailable,503,PROOF_UNAVAILABLE,30", "rate-limited,503,PROOF_UNAVAILABLE,60"})
    void renewalFailureDoesNotInstallAccessAndOutagesPreserveCachedReads(String failure, int status,
                                                                       String code, int retrySeconds) throws Exception {
        Proof live = f.prove();
        Proof other = f.issued(B, Set.of(AccountGrant.values()), Set.of());
        failProof(failure);
        var response = refused(f.http.perform(authorized(post("/api/session/renew"), live)
                .header("Origin", "http://localhost").header("X-GW2-API-Key", KEY)), status, code);
        assertEquals(retrySeconds == 0 ? null : Integer.toString(retrySeconds), response.getHeader("Retry-After"));
        assertEquals(2, f.state.proofCalls);
        if (failure.equals("invalid")) {
            f.http.perform(authorized(get("/api/account/bank"), live))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("SESSION_REQUIRED"));
            assertEquals(1, f.state.sessions.size(), "definitive rejection revokes only the originating lease");
        } else {
            assertEquals(2, f.state.sessions.size(), "no failed renewal creates an extra lease");
            if (retrySeconds > 0) cachedRead(live, A);
        }
        cachedRead(other, B);
        assertEquals(2, f.state.proofCalls, "cached reads do not re-prove through an outage");
    }

    @Test
    void aDifferentAccountsValidKeyCannotRenewOrRevokeTheCurrentAccount() throws Exception {
        Proof live = f.prove();
        refused(f.http.perform(authorized(post("/api/session/renew"), live).header("Origin", "http://localhost")
                .header("X-GW2-API-Key", OTHER_KEY)), 403, "ACCOUNT_MISMATCH");
        assertEquals(1, f.state.sessions.size());
        cachedRead(live, A);
        assertEquals(2, f.state.proofCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/session/renew", "/api/session/logout"})
    void foreignOriginCannotRenewOrLogOutAnExistingSession(String path) throws Exception {
        Proof live = f.prove();
        refused(f.http.perform(authorized(post(path), live).header("Origin", "https://foreign.example")
                .header("X-GW2-API-Key", KEY)), 403, "ORIGIN_REJECTED");
        cachedRead(live, A);
        assertEquals(1, f.state.proofCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/session", "/api/session/renew"})
    void deletionBusyProofInstallationReturnsRetryableFailureWithoutReplacingTheSession(String path) throws Exception {
        Proof live = f.prove();
        var normalProof = f.state.proof;
        var deletion = f.state.access.lifecycle().beginDeletion(UUID.fromString(A), () -> false, 0);
        assertNotNull(deletion.exclusive());
        // Interrupt at the proof boundary, immediately before the real install attempts to enter
        // the closed gate. This exercises its unavailable path without a ten-second timeout/sleep.
        f.state.proof = key -> {
            var proved = normalProof.prove(key);
            Thread.currentThread().interrupt();
            return proved;
        };
        try (var ignored = deletion.exclusive()) {
            try {
                refused(f.http.perform(authorized(post(path), live).header("Origin", "http://localhost")
                        .header("X-GW2-API-Key", KEY)), 503, "DATA_STORE_UNAVAILABLE");
            } finally {
                Thread.interrupted(); // never leak the controlled interruption into another test
            }
        } finally {
            f.state.proof = normalProof;
        }
        assertEquals(1, f.state.sessions.size(), "failed installation did not replace or revoke the old lease");
        cachedRead(live, A);
        assertEquals(2, f.state.proofCalls);
    }

    @Test
    void successfulRenewalRotatesAccessWithoutExtendingTheOldCookiesAbsoluteLease() throws Exception {
        Proof old = f.prove();
        f.state.clock.now = START.plusSeconds(840);
        var response = f.http.perform(authorized(post("/api/session/renew"), old)
                        .header("Origin", "http://localhost").header("X-GW2-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountId").value(A))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-17T12:29:00Z"))
                .andReturn().getResponse();
        Proof renewed = proof(response);
        assertNotEquals(old.cookie().getValue(), renewed.cookie().getValue());
        assertNotEquals(old.accessContext(), renewed.accessContext());
        assertEquals(renewed.accessContext(), response.getHeader("X-Account-Context"));
        cachedRead(old, A);
        f.state.clock.now = START.plusSeconds(900);
        f.http.perform(authorized(get("/api/account/bank"), old))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("SESSION_REQUIRED"));
        cachedRead(renewed, A);
        assertEquals(2, f.state.proofCalls);
        assertFalse(response.getContentAsString().contains(KEY));
        assertFalse(response.getContentAsString().contains(renewed.cookie().getValue()));
    }

    @ParameterizedTest
    @CsvSource({"expires,SESSION_REQUIRED", "deleted,ACCOUNT_DATA_DELETED"})
    void authorityIsRecheckedIfItEndsWhileTheRenewalProofIsInFlight(String event, String code) throws Exception {
        Proof live = f.prove();
        f.state.proof = key -> {
            if (event.equals("expires")) f.state.clock.now = START.plusSeconds(900);
            else f.state.access.invalidateAccountSessions(A);
            return new AccountProof(A, Set.of(AccountGrant.values()), Set.of(), null);
        };
        refused(f.http.perform(authorized(post("/api/session/renew"), live).header("Origin", "http://localhost")
                .header("X-GW2-API-Key", KEY)), 401, code);
        assertEquals(2, f.state.proofCalls);
        assertEquals(event.equals("expires") ? 0 : 1, f.state.sessions.size(), "no replacement session was installed");
        assertEquals(0, f.state.handlerCalls);
    }

    @ParameterizedTest
    @CsvSource({"missing-cookie,401,SESSION_REQUIRED", "unknown-cookie,401,SESSION_REQUIRED",
            "expired,401,SESSION_REQUIRED", "wrong-context,403,ACCESS_CONTEXT_MISMATCH",
            "deleted,401,ACCOUNT_DATA_DELETED"})
    void refusedLogoutCannotClearTheBrowserCookieOrRevokeAnUnrelatedSession(String condition, int status,
                                                                          String code) throws Exception {
        Proof live = f.prove();
        Proof other = f.issued(B, Set.of(AccountGrant.values()), Set.of());
        var request = post("/api/session/logout").header("Origin", "http://localhost")
                .header("X-Account-Context", condition.equals("wrong-context") ? "wrong" : live.accessContext());
        if (!condition.equals("missing-cookie")) request.cookie(condition.equals("unknown-cookie")
                ? new Cookie("GW2_SESSION", "unknown") : live.cookie());
        if (condition.equals("expired")) f.state.clock.now = START.plusSeconds(900);
        if (condition.equals("deleted")) f.state.access.invalidateAccountSessions(A);
        refused(f.http.perform(request), status, code);
        if (!condition.equals("expired")) cachedRead(other, B);
        if (condition.equals("wrong-context")) cachedRead(live, A);
        assertEquals(1, f.state.proofCalls, "logout and reads never send an API key upstream");
    }

    @ParameterizedTest
    @CsvSource({"http,localhost,80,false,false", "https,localhost,443,true,true",
            "http,example.test,80,false,true", "http,localhost,8181,false,false",
            "https,example.test,8443,true,true"})
    void issuanceAndLogoutKeepTheSameHostOnlyCookiePolicy(String scheme, String host, int port,
                                                         boolean secureRequest, boolean secureCookie) throws Exception {
        String origin = scheme + "://" + host + ((port == 80 && scheme.equals("http"))
                || (port == 443 && scheme.equals("https")) ? "" : ":" + port);
        var response = f.http.perform(post("/api/session").header("Origin", origin)
                        .header("X-GW2-API-Key", "  " + KEY + "  ")
                        .with(request -> { request.setScheme(scheme); request.setServerName(host);
                            request.setServerPort(port); request.setSecure(secureRequest); return request; }))
                .andExpect(status().isOk()).andReturn().getResponse();
        Proof live = proof(response);
        String issued = response.getHeader("Set-Cookie");
        assertNotNull(issued);
        assertCookie(issued, secureCookie);
        assertFalse(issued.contains("Max-Age"));
        var logout = f.http.perform(authorized(post("/api/session/logout"), live).header("Origin", origin)
                        .with(request -> { request.setScheme(scheme); request.setServerName(host);
                            request.setServerPort(port); request.setSecure(secureRequest); return request; }))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertCookie(logout.getHeader("Set-Cookie"), secureCookie);
        assertTrue(logout.getHeader("Set-Cookie").contains("Max-Age=0"));
        f.http.perform(authorized(get("/api/account/bank"), live)).andExpect(status().isUnauthorized());
        assertEquals(1, f.state.proofCalls);
    }

    @Test
    void explicitlyConfiguredOriginIsTrimmedAndDoesNotPermitAnUnlistedOrigin() throws Exception {
        f.close();
        f = new AccountAccessHttpFixture(" https://frontend.example, , https://second.example ");
        f.http.perform(post("/api/session").header("Origin", "HTTPS://FRONTEND.EXAMPLE")
                        .header("X-GW2-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        refused(f.http.perform(post("/api/session").header("Origin", "https://unlisted.example")
                .header("X-GW2-API-Key", KEY)), 403, "ORIGIN_REJECTED");
        assertEquals(1, f.state.proofCalls);
    }

    private void cachedRead(Proof proof, String account) throws Exception {
        f.http.perform(authorized(get("/api/account/bank"), proof)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account))
                .andExpect(header().string("X-Account-Context", proof.accessContext()));
    }

    private void failProof(String failure) {
        f.state.proof = key -> {
            switch (failure) {
                case "invalid" -> throw new InvalidCredentialException("offline refusal");
                case "malformed" -> throw new MalformedProofException("offline malformed response");
                case "unavailable" -> throw new ProofUnavailableException("offline outage", Duration.ofSeconds(30));
                case "rate-limited" -> throw new ProofUnavailableException("offline rate limit", Duration.ofSeconds(60));
                default -> throw new AssertionError("unknown test case");
            }
        };
    }

    private static MockHttpServletResponse refused(ResultActions action, int status, String code) throws Exception {
        var response = action.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(code))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("X-Account-Context"))
                .andExpect(jsonPath("$.accountId").doesNotExist()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(KEY));
        assertFalse(response.getContentAsString().contains(OTHER_KEY));
        assertFalse(response.getContentAsString().contains(A));
        assertFalse(response.getContentAsString().contains(B));
        return response;
    }

    private static void assertCookie(String cookie, boolean secure) {
        assertNotNull(cookie);
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertEquals(secure, cookie.contains("; Secure"));
        assertFalse(cookie.contains("Domain="));
        assertFalse(cookie.contains(KEY));
    }
}
