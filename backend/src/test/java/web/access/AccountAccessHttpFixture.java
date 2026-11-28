package web.access;

import application.access.AccountAccessService;
import application.access.AccountContext;
import domain.access.AccountGrant;
import domain.access.AccountProof;
import domain.access.AccountProofPort;
import application.access.AccountSessions;
import domain.access.InvalidCredentialException;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A database-free MVC context with the production guard registration and session controller.
 * Only proof and time are controlled. The scoped handler records dispatch and scope, not production
 * payloads; existing controller tests remain responsible for calculation/refresh/read responses.
 */
final class AccountAccessHttpFixture implements AutoCloseable {
    static final String A = "a1111111-2222-3333-4444-555555555555";
    static final String B = "b2222222-2222-3333-4444-555555555555";
    static final String KEY = "offline-http-key-a";
    static final String OTHER_KEY = "offline-http-key-b";
    static final Instant START = Instant.parse("2026-11-17T12:00:00Z");

    final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
    final State state;
    final MockMvc http;

    AccountAccessHttpFixture() {
        this("");
    }

    AccountAccessHttpFixture(String allowedOrigins) {
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("offline-http-test",
                Map.of("gw2.account-access.enforce", "true", "gw2.account-access.test-fixtures", "false",
                        "gw2.account-access.allowed-origins", allowedOrigins)));
        context.register(Config.class);
        context.refresh();
        state = context.getBean(State.class);
        http = MockMvcBuilders.webAppContextSetup(context).build();
    }

    Proof prove() throws Exception {
        var response = http.perform(post("/api/session").header("Origin", "http://localhost")
                .header("X-GW2-API-Key", KEY)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return proof(response);
    }

    static Proof proof(MockHttpServletResponse response) throws Exception {
        String cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie);
        String id = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        return new Proof(new Cookie("GW2_SESSION", id),
                JsonPath.read(response.getContentAsString(), "$.accessContext"),
                JsonPath.read(response.getContentAsString(), "$.accountId"));
    }

    Proof issued(String account, Set<AccountGrant> grants, Set<String> endpoints) {
        var issued = state.sessions.establish(new AccountProof(account, grants, endpoints, null));
        return new Proof(new Cookie("GW2_SESSION", issued.sessionId()),
                issued.context().accessContext(), issued.context().accountId());
    }

    static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, Proof proof) {
        return request.cookie(proof.cookie()).header("X-Account-Context", proof.accessContext());
    }

    @Override public void close() {
        context.close();
    }

    record Proof(Cookie cookie, String accessContext, String accountId) {}

    static final class State {
        final MutableClock clock = new MutableClock();
        final AccountSessions sessions = new AccountSessions(clock, 64);
        int proofCalls;
        int handlerCalls;
        AccountProofPort proof = key -> {
            if (!KEY.equals(key) && !OTHER_KEY.equals(key)) throw new InvalidCredentialException("offline refusal");
            return new AccountProof(OTHER_KEY.equals(key) ? B : A, Set.of(AccountGrant.values()), Set.of(), null);
        };
        final AccountAccessService access = new AccountAccessService(key -> {
            proofCalls++;
            return proof.prove(key);
        }, sessions);
    }

    static final class MutableClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AccountAccessWebConfig.class)
    static class Config {
        @Bean State testState() { return new State(); }
        @Bean AccountAccessService access(State state) { return state.access; }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AccountAccessHttp accessHttp(org.springframework.core.env.Environment env) {
            return new AccountAccessHttp(env.getProperty("gw2.account-access.allowed-origins", ""));
        }
        @Bean AccountAccessApiController sessions(AccountAccessService access, AccountAccessHttp http) {
            return new AccountAccessApiController(access, http);
        }
        @Bean ScopeProbe scopeProbe(State state) { return new ScopeProbe(state); }
    }

    @TestComponent
    @RestController
    static final class ScopeProbe {
        private final State state;
        ScopeProbe(State state) { this.state = state; }

        @RequestMapping({"/api/account/bank", "/api/account/materials", "/api/account/luck",
                "/api/account/characters", "/api/account/characters/Test/build-tabs",
                "/api/sync/account", "/api/sync/account/crafting-profit", "/api/sync/account/bank",
                "/api/sync/account/materials", "/api/sync/account/wallet", "/api/sync/account/characters", "/api/sync/account/luck",
                "/api/sync/account/character-equipment-tabs", "/api/sync/account/character-build-tabs",
                "/api/crafting/profit", "/api/crafting/profit/resolution", "/api/crafting/selector-options",
                "/api/crafting/discovery", "/api/crafting/discovery/resolution"})
        Map<String, String> scoped(HttpServletRequest request) throws Exception {
            state.handlerCalls++;
            AccountContext resolved = RequestAccountContext.required(request);
            return Map.of("accountId", RequestAccountContext.scopeOf(request).id(),
                    "accessContext", resolved.accessContext(),
                    "body", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }

        @RequestMapping("/api/crafting/ingredient-search")
        Map<String, Boolean> global(HttpServletRequest request) {
            state.handlerCalls++;
            return Map.of("hasAccountContext", RequestAccountContext.of(request).isPresent());
        }
    }
}
