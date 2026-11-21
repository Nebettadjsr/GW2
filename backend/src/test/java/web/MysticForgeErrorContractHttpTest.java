package web;

import application.CachedCraftingProfitService;
import application.ScopedAccountRefreshCoordinator;
import jakarta.servlet.http.Cookie;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Forge project route shares the structured error contract; its own 403/401 statuses are preserved. */
class MysticForgeErrorContractHttpTest {
    private final AtomicInteger calls = new AtomicInteger();

    private MockMvc mvc(boolean unknown, ScopedAccountRefreshCoordinator.Reason rejection) {
        CachedCraftingProfitService service = new CachedCraftingProfitService(null, null, null, null, null, null, null, null) {
            @Override
            public ForgeProjectOutcome forgeProject(AccountScope scope, int recipeId, long target, craft.ForgeSettings settings,
                                                    KeyedAdmission keyed, application.access.AccountContext readContext) {
                calls.incrementAndGet();
                if (rejection != null) throw new ScopedAccountRefreshCoordinator.Rejected(rejection);
                return new ForgeProjectOutcome(null, unknown, null);
            }
        };
        return MockMvcBuilders
                .standaloneSetup(new MysticForgeApiController(service, new web.access.AccountAccessHttp(""), null))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(String body) {
        return post("/api/mystic-forge/project").contentType("application/json").content(body);
    }

    @Test
    void unknownNegativeRecipeIsStructured400NotA500() throws Exception {
        mvc(true, null).perform(req("{\"recipeId\":-987654321,\"target\":1}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMenuSettingValuesAreStructured400() throws Exception {
        for (String bad : new String[] {"\"priceMode\":\"WAIT\"", "\"daily\":\"WAIT\"", "\"reduce\":\"ALL\""}) {
            mvc(false, null).perform(req("{\"recipeId\":-1,\"target\":1," + bad + "}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    @Test
    void invalidBodyTargetAndNonNegativeRecipeAreStructured400WithoutWork() throws Exception {
        MockMvc m = mvc(false, null);
        for (String body : new String[]{"{\"recipeId\":0,\"target\":1}", "{\"recipeId\":5,\"target\":1}",
                "{\"recipeId\":-1,\"target\":0}", "{\"target\":1}"}) {
            m.perform(req(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        m.perform(req("not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        assertEquals(0, calls.get());
    }

    @Test
    void foreignOriginKeyedRequestStays403AndProofRejectionsKeepTheirStatuses() throws Exception {
        mvc(false, null).perform(req("{\"recipeId\":-1}").header("X-GW2-API-Key", "k").cookie(new Cookie("GW2_SESSION", "s-1"))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ORIGIN_REJECTED"));
        assertEquals(0, calls.get());
        mvc(false, ScopedAccountRefreshCoordinator.Reason.ACCOUNT_MISMATCH)
                .perform(req("{\"recipeId\":-1}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCOUNT_ACCESS_DENIED"));
    }
}
