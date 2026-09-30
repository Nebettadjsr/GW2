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
                new application.AccountRefreshService(), new application.TradingPostPriceCacheStatusService(
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
}
