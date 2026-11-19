package web.access;

import application.access.AccountGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static application.access.AccountGrant.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static web.access.AccountAccessHttpFixture.*;

/**
 * #111: production MVC guard registration, route/source mapping and context propagation.
 * The table below states the documented source requirements independently of operationFor().
 */
class AccountAccessRouteMappingHttpTest {
    private AccountAccessHttpFixture f;

    @BeforeEach void open() { f = new AccountAccessHttpFixture(); }
    @AfterEach void close() { f.close(); }

    private record Route(String method, String path, Set<AccountGrant> grants, Set<String> endpoints) {
        MockHttpServletRequestBuilder request() {
            return method.equals("GET") ? get(path) : post(path).header("Origin", "http://localhost");
        }
        @Override public String toString() { return method + " " + path; }
    }

    static Stream<Route> routes() {
        Set<AccountGrant> inventory = Set.of(ACCOUNT, INVENTORIES);
        Set<AccountGrant> characters = Set.of(ACCOUNT, INVENTORIES, CHARACTERS);
        Set<AccountGrant> luck = Set.of(ACCOUNT, PROGRESSION, UNLOCKS);
        Set<AccountGrant> profit = Set.of(ACCOUNT, INVENTORIES, CHARACTERS, UNLOCKS);
        Set<String> profitUrls = Set.of("/v2/account/bank", "/v2/account/materials", "/v2/account/recipes", "/v2/characters");
        Set<AccountGrant> templates = Set.of(ACCOUNT, INVENTORIES, CHARACTERS, BUILDS);
        return Stream.of(
                new Route("GET", "/api/account/bank", inventory, Set.of("/v2/account/bank")),
                new Route("GET", "/api/account/materials", inventory, Set.of("/v2/account/materials")),
                new Route("GET", "/api/account/luck", luck, Set.of("/v2/account/luck")),
                new Route("POST", "/api/sync/account", Set.of(ACCOUNT, INVENTORIES, CHARACTERS, UNLOCKS, PROGRESSION),
                        Set.of("/v2/account", "/v2/account/bank", "/v2/account/materials", "/v2/account/recipes",
                                "/v2/account/luck", "/v2/characters")),
                new Route("POST", "/api/sync/account/crafting-profit", profit, profitUrls),
                new Route("POST", "/api/sync/account/bank", inventory, Set.of("/v2/account/bank")),
                new Route("POST", "/api/sync/account/materials", inventory, Set.of("/v2/account/materials")),
                new Route("POST", "/api/sync/account/wallet", Set.of(ACCOUNT, WALLET), Set.of("/v2/account/wallet")),
                new Route("POST", "/api/sync/account/characters", characters, Set.of("/v2/characters")),
                new Route("POST", "/api/sync/account/luck", luck, Set.of("/v2/account/luck")),
                new Route("POST", "/api/sync/account/character-equipment-tabs", templates,
                        Set.of("/v2/characters", "/v2/characters/:id/equipmenttabs")),
                new Route("POST", "/api/sync/account/character-build-tabs", templates,
                        Set.of("/v2/characters", "/v2/characters/:id/buildtabs")),
                new Route("POST", "/api/crafting/profit", profit, profitUrls),
                new Route("POST", "/api/crafting/profit/resolution", profit, profitUrls),
                new Route("POST", "/api/crafting/discovery", profit, profitUrls),
                new Route("POST", "/api/crafting/discovery/resolution", profit, profitUrls),
                new Route("GET", "/api/crafting/selector-options", characters, Set.of("/v2/characters")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void registeredRoutesRequireExactlyTheirDocumentedSourcesBeforeDispatch(Route route) throws Exception {
        denied(f.http.perform(route.request()), 401, "SESSION_REQUIRED");
        assertEquals(0, f.state.handlerCalls);

        Proof permitted = f.issued(A, route.grants(), route.endpoints());
        f.http.perform(authorized(route.request(), permitted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(A))
                .andExpect(jsonPath("$.accessContext").value(permitted.accessContext()))
                .andExpect(header().string("X-Account-Context", permitted.accessContext()))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(1, f.state.handlerCalls);

        for (AccountGrant missing : route.grants()) {
            if (missing == ACCOUNT) continue; // every session proof must already establish identity
            Set<AccountGrant> narrowed = new HashSet<>(route.grants());
            narrowed.remove(missing);
            Proof insufficient = f.issued(A, narrowed, route.endpoints());
            denied(f.http.perform(authorized(route.request(), insufficient)), 403, "ACCOUNT_ACCESS_DENIED");
            assertEquals(1, f.state.handlerCalls, "missing " + missing + " must stop before the handler");
        }
        Proof restricted = f.issued(A, route.grants(), Set.of("/v2/account"));
        denied(f.http.perform(authorized(route.request(), restricted)), 403, "ACCOUNT_ACCESS_DENIED");
        assertEquals(1, f.state.handlerCalls, "upstream URL restrictions also govern cached sources");
        assertEquals(0, f.state.proofCalls, "the guard resolves the lease without another upstream proof");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-cookie", "empty-cookie", "unrelated-cookie", "missing-context",
            "blank-context", "wrong-context", "expired"})
    void unusableSessionOrContextNeverReachesTheHandler(String condition) throws Exception {
        Proof live = f.issued(A, Set.of(ACCOUNT, INVENTORIES), Set.of());
        Cookie cookie = switch (condition) {
            case "unknown-cookie" -> new Cookie("GW2_SESSION", "unknown");
            case "empty-cookie" -> new Cookie("GW2_SESSION", "");
            case "unrelated-cookie" -> new Cookie("another-cookie", live.cookie().getValue());
            default -> live.cookie();
        };
        String context = switch (condition) {
            case "blank-context" -> " ";
            case "wrong-context" -> "different-tab";
            default -> live.accessContext();
        };
        if (condition.equals("expired")) f.state.clock.now = START.plusSeconds(900);
        boolean contextFailure = condition.endsWith("context");
        var request = get("/api/account/bank").cookie(cookie);
        if (!condition.equals("missing-context")) request.header("X-Account-Context", context);
        denied(f.http.perform(request),
                contextFailure ? 403 : 401, contextFailure ? "ACCOUNT_ACCESS_DENIED" : "SESSION_REQUIRED");
        assertEquals(0, f.state.handlerCalls);
        assertEquals(0, f.state.proofCalls);
    }

    @Test
    void queryAndParameterAccountAssertionsCannotSelectAnotherAccountsScope() throws Exception {
        Proof live = f.issued(A, Set.of(ACCOUNT, INVENTORIES), Set.of());
        f.http.perform(authorized(get(URI.create("/api/account/bank?hint=wood&accountId=" + A.toUpperCase())), live))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(A));
        denied(f.http.perform(authorized(get(URI.create("/api/account/bank?hint=wood&accountId=" + B))
                .param("accountId", A), live)), 403, "ACCOUNT_ACCESS_DENIED");
        denied(f.http.perform(authorized(get("/api/account/bank").param("accountId", B), live)),
                403, "ACCOUNT_ACCESS_DENIED");
        f.http.perform(authorized(get("/api/account/bank").param("accountId", " "), live))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(A));
        assertEquals(2, f.state.handlerCalls, "foreign consistency assertions cannot dispatch account work");
    }

    @Test
    void formBodyIsNotConsumedByTheGuardAndNeverBecomesTheVerifiedScope() throws Exception {
        Proof live = f.issued(A, Set.of(ACCOUNT, INVENTORIES), Set.of());
        String body = "accountId=" + B + "&note=untouched";
        f.http.perform(authorized(post("/api/account/bank"), live)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(A))
                .andExpect(jsonPath("$.body").value(body));
        assertEquals(1, f.state.handlerCalls);
        // This observes the guard's body/scope contract, not permission for a production read to accept a form.
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/account;x=1/bank", "/api/%61ccount/bank", "/gw2/api/account/bank"})
    void mvcMappedPathsKeepTheSameGrantBoundary(String path) throws Exception {
        Proof identityOnly = f.issued(A, Set.of(ACCOUNT), Set.of());
        var request = get(URI.create(path));
        if (path.startsWith("/gw2/")) request.contextPath("/gw2");
        denied(f.http.perform(authorized(request, identityOnly)), 403, "ACCOUNT_ACCESS_DENIED");
        assertEquals(0, f.state.handlerCalls);
    }

    @Test
    void characterReadDefersGrantStatesButStillRequiresALiveVerifiedContext() throws Exception {
        Proof identityOnly = f.issued(A, Set.of(ACCOUNT), Set.of());
        for (String path : new String[]{"/api/account/characters", "/api/account/characters/Test/build-tabs"}) {
            denied(f.http.perform(get(path)), 401, "SESSION_REQUIRED");
            f.http.perform(authorized(get(path), identityOnly)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(A));
        }
        assertEquals(2, f.state.handlerCalls);
        // The real character controller, tested elsewhere, reports GRANT_REQUIRED/ENDPOINT_RESTRICTED.
    }

    @Test
    void scopeIsPerRequestAndGlobalLookupsHaveNoInheritedAccountContext() throws Exception {
        Proof a = f.issued(A, Set.of(ACCOUNT, INVENTORIES), Set.of());
        Proof b = f.issued(B, Set.of(ACCOUNT, INVENTORIES), Set.of());
        for (Proof proof : new Proof[]{a, b}) {
            f.http.perform(authorized(get("/api/account/bank"), proof)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(proof.accountId()))
                    .andExpect(jsonPath("$.accessContext").value(proof.accessContext()));
        }
        denied(f.http.perform(get("/api/account/bank").cookie(b.cookie())
                .header("X-Account-Context", a.accessContext())), 403, "ACCOUNT_ACCESS_DENIED");
        f.http.perform(authorized(get("/api/crafting/ingredient-search"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAccountContext").value(false))
                .andExpect(header().doesNotExist("X-Account-Context"));
        assertEquals(3, f.state.handlerCalls);
        var unscoped = new MockHttpServletRequest();
        assertTrue(RequestAccountContext.of(unscoped).isEmpty());
        assertThrows(IllegalStateException.class, () -> RequestAccountContext.required(unscoped),
                "a consumer cannot silently obtain a default account outside the guard");
    }

    private static void denied(ResultActions action, int status, String code) throws Exception {
        var response = action.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(code))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-Account-Context"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(A));
        assertFalse(response.getContentAsString().contains(B));
        assertFalse(response.getContentAsString().contains(KEY));
    }
}
