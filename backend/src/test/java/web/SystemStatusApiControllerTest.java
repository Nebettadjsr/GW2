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
                // Generic projection (STORY-STATUS-001): coarse states only; details moved to the owner route.
                .andExpect(jsonPath("$.globalHealth").value("NOT_RUN"))
                .andExpect(jsonPath("$.priceHealth").value("NOT_RUN"))
                .andExpect(jsonPath("$.running").doesNotExist())
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist())
                .andExpect(jsonPath("$.accountLastRefreshedAt").doesNotExist())
                .andExpect(jsonPath("$.cachedPriceItems").doesNotExist());
    }

    @Test
    void projectsOnlyTheAuthorizedAccountsGrantLimitedFreshnessAndRejectsBadProof() throws Exception {
        var sessions = new application.access.AccountSessions(java.time.Clock.systemUTC(), 4);
        var access = new application.access.AccountAccessService(key -> { throw new application.access.InvalidCredentialException("no"); }, sessions);
        var issued = sessions.establish(new application.access.AccountProof("11111111-2222-3333-4444-555555555555",
                java.util.Set.of(application.access.AccountGrant.ACCOUNT, application.access.AccountGrant.INVENTORIES), java.util.Set.of(), null));
        var seen = new java.util.ArrayList<java.util.Set<String>>();
        var done = java.time.Instant.parse("2026-10-25T10:00:00Z");
        var activity = new java.util.ArrayList<String>();
        var gate = new application.ScopedSourceGate((scope, source) -> java.util.Optional.empty(),
                (scope, at) -> activity.add(scope.id()), java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(15));
        MockMvc mvc = standaloneSetup(new SystemStatusApiController(new GlobalDataRefreshService(),
                new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
                    @Override public CacheStatus cacheStatus() { return new CacheStatus(0, 0, null); }
                }), access, (scope, sources) -> {
                    seen.add(sources);
                    var states = new java.util.LinkedHashMap<String, repo.AccountSourceStateRepository.SourceState>();
                    for (String s : sources) states.put(s, new repo.AccountSourceStateRepository.SourceState(s, 1, done));
                    return states;
                }, gate)).build();

        var cookie = new jakarta.servlet.http.Cookie(web.access.AccountAccessHttp.SESSION_COOKIE, issued.sessionId());
        mvc.perform(get("/api/system/status").cookie(cookie)
                        .header(web.access.AccountAccessHttp.ACCESS_CONTEXT_HEADER, issued.context().accessContext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountLastRefreshedAt").exists())
                .andExpect(jsonPath("$.accountRefreshScope").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("LUCK"))));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("11111111-2222-3333-4444-555555555555"), activity,
                "ordinary authorized use is recorded as activity");
        org.junit.jupiter.api.Assertions.assertFalse(seen.get(0).contains("LUCK"), "ungranted sources are not projected");

        mvc.perform(get("/api/system/status").cookie(cookie).header(
                        web.access.AccountAccessHttp.ACCESS_CONTEXT_HEADER, "stale"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/system/status").cookie(new jakarta.servlet.http.Cookie(
                        web.access.AccountAccessHttp.SESSION_COOKIE, "unknown")))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(1, activity.size(), "rejected proof records no activity");
    }

    @Test
    void overviewIsOwnAccountLimitedByGrantsAndUrlRestrictionsAndQueriedOnlyForPermittedSources() throws Exception {
        var sessions = new application.access.AccountSessions(java.time.Clock.systemUTC(), 4);
        var access = new application.access.AccountAccessService(key -> { throw new application.access.InvalidCredentialException("no"); }, sessions);
        var grants = java.util.Set.of(application.access.AccountGrant.ACCOUNT, application.access.AccountGrant.CHARACTERS,
                application.access.AccountGrant.INVENTORIES, application.access.AccountGrant.UNLOCKS);
        // URL restriction excludes the recipes endpoint although the grant is present.
        var restrictions = java.util.Set.of("/v2/characters", "/v2/account/materials");
        var issued = sessions.establish(new application.access.AccountProof("11111111-2222-3333-4444-555555555555",
                grants, restrictions, null));
        var asked = new java.util.ArrayList<java.util.Set<String>>();
        var overview = new application.AccountOverviewService(() -> { throw new java.sql.SQLException("x"); },
                new repo.AccountOverviewRepository(), java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(15)) {
            @Override public java.util.Map<String, SourceOverview> read(model.AccountScope scope, java.util.Set<String> sources) {
                asked.add(java.util.Set.copyOf(sources));
                var m = new java.util.LinkedHashMap<String, SourceOverview>();
                for (String s : sources) m.put(s, new SourceOverview("CURRENT", 3L, "MATERIALS".equals(s) ? 9L : null,
                        java.time.Instant.parse("2026-11-11T10:00:00Z")));
                return m;
            }
        };
        MockMvc mvc = standaloneSetup(new SystemStatusApiController(new GlobalDataRefreshService(),
                new application.TradingPostPriceCacheStatusService(new repo.tp.TpPriceRepository() {
                    @Override public CacheStatus cacheStatus() { return new CacheStatus(0, 0, null); }
                }), access, (scope, sources) -> new java.util.LinkedHashMap<>(), null, null, null, overview)).build();
        var cookie = new jakarta.servlet.http.Cookie(web.access.AccountAccessHttp.SESSION_COOKIE, issued.sessionId());
        mvc.perform(get("/api/system/status").cookie(cookie)
                        .header(web.access.AccountAccessHttp.ACCESS_CONTEXT_HEADER, issued.context().accessContext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountOverview.characters.state").value("CURRENT"))
                .andExpect(jsonPath("$.accountOverview.characters.count").value(3))
                .andExpect(jsonPath("$.accountOverview.materials.total").value(9))
                .andExpect(jsonPath("$.accountOverview.recipes").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(java.util.Set.of("CHARACTERS", "MATERIALS")), asked,
                "an unpermitted source is never queried");
        // Anonymous callers get no overview and cause no query.
        mvc.perform(get("/api/system/status")).andExpect(jsonPath("$.accountOverview").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(1, asked.size());
    }
}
