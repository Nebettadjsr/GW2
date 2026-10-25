package web;

import application.GlobalDataRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SystemStatusApiControllerTest {
    @Test
    void exposesProcessLocalGlobalRefreshStatus() throws Exception {
        var service = new GlobalDataRefreshService();
        MockMvc mvc = standaloneSetup(new SystemStatusApiController(service,
                new application.TradingPostPriceCacheStatusService(
                new repo.tp.TpPriceRepository() {
                    @Override public CacheStatus cacheStatus() {
                        return new CacheStatus(0, 0, null);
                    }
                }))).build();

        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist())
                .andExpect(jsonPath("$.accountLastRefreshedAt").doesNotExist())
                .andExpect(jsonPath("$.cachedPriceItems").value(0))
                .andExpect(jsonPath("$.stalePriceItems").value(0));
    }

    @Test
    void projectsOnlyTheAuthorizedAccountsGrantLimitedFreshnessAndRejectsBadProof() throws Exception {
        var sessions = new application.access.AccountSessions(java.time.Clock.systemUTC(), 4);
        var access = new application.access.AccountAccessService(key -> { throw new application.access.InvalidCredentialException("no"); }, sessions);
        var issued = sessions.establish(new application.access.AccountProof("11111111-2222-3333-4444-555555555555",
                java.util.Set.of(application.access.AccountGrant.ACCOUNT, application.access.AccountGrant.INVENTORIES), java.util.Set.of(), null));
        var seen = new java.util.ArrayList<java.util.Set<String>>();
        var done = java.time.Instant.parse("2026-10-25T10:00:00Z");
        MockMvc mvc = standaloneSetup(new SystemStatusApiController(new GlobalDataRefreshService(),
                new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
                    @Override public CacheStatus cacheStatus() { return new CacheStatus(0, 0, null); }
                }), access, (scope, sources) -> {
                    seen.add(sources);
                    var states = new java.util.LinkedHashMap<String, repo.AccountSourceStateRepository.SourceState>();
                    for (String s : sources) states.put(s, new repo.AccountSourceStateRepository.SourceState(s, 1, done));
                    return states;
                })).build();

        var cookie = new jakarta.servlet.http.Cookie(web.access.AccountAccessHttp.SESSION_COOKIE, issued.sessionId());
        mvc.perform(get("/api/system/status").cookie(cookie)
                        .header(web.access.AccountAccessHttp.ACCESS_CONTEXT_HEADER, issued.context().accessContext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLastRefreshedAt").exists())
                .andExpect(jsonPath("$.accountRefreshScope").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("LUCK"))));
        org.junit.jupiter.api.Assertions.assertFalse(seen.get(0).contains("LUCK"), "ungranted sources are not projected");

        mvc.perform(get("/api/system/status").cookie(cookie).header(
                        web.access.AccountAccessHttp.ACCESS_CONTEXT_HEADER, "stale"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/system/status").cookie(new jakarta.servlet.http.Cookie(
                        web.access.AccountAccessHttp.SESSION_COOKIE, "unknown")))
                .andExpect(status().isUnauthorized());
    }
}
