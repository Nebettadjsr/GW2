package web;

import application.CachedCraftingProfitService;
import application.ScopedAccountRefreshCoordinator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;
import craft.CraftingSettings;
import model.AccountScope;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STORY-API-011: keyed Profit is a state change (origin first) and rejected proof is 401/403/503, not a 200. */
class CraftingProfitKeyedAdmissionHttpTest {

    private final AtomicInteger calls = new AtomicInteger();

    private MockMvc mvc(ScopedAccountRefreshCoordinator.Reason rejection) {
        CachedCraftingProfitService service = new CachedCraftingProfitService(
                null, null, null, null, null, null, null, null, null) {
            @Override
            public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings,
                                        KeyedAdmission keyed) {
                calls.incrementAndGet();
                if (rejection != null) throw new ScopedAccountRefreshCoordinator.Rejected(rejection);
                throw new AssertionError("not reached");
            }
        };
        return MockMvcBuilders
                .standaloneSetup(new CraftingProfitApiController(service, new web.access.AccountAccessHttp("")))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder keyed(String origin) {
        MockHttpServletRequestBuilder request = post("/api/crafting/profit")
                .header("X-GW2-API-Key", "transient-key").cookie(new Cookie("GW2_SESSION", "s-1"))
                .contentType("application/json").content("{}");
        return origin == null ? request : request.header("Origin", origin);
    }

    @Test
    void foreignOriginIsRejectedBeforeProofOrAnyWork() throws Exception {
        mvc(null).perform(keyed("https://evil.example")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ORIGIN_REJECTED"));
        mvc(null).perform(keyed(null)).andExpect(status().isForbidden());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectedProofMapsToApi010StatusesInsteadOfACalculation() throws Exception {
        String origin = "http://localhost";
        mvc(ScopedAccountRefreshCoordinator.Reason.INVALID_CREDENTIAL).perform(keyed(origin))
                .andExpect(status().isUnauthorized());
        mvc(ScopedAccountRefreshCoordinator.Reason.SESSION).perform(keyed(origin))
                .andExpect(status().isUnauthorized());
        mvc(ScopedAccountRefreshCoordinator.Reason.ACCOUNT_MISMATCH).perform(keyed(origin))
                .andExpect(status().isForbidden());
        mvc(ScopedAccountRefreshCoordinator.Reason.INSUFFICIENT_GRANTS).perform(keyed(origin))
                .andExpect(status().isForbidden());
        mvc(ScopedAccountRefreshCoordinator.Reason.UPSTREAM_UNAVAILABLE).perform(keyed(origin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "30"));
    }
}
