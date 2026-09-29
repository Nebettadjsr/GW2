package web;

import application.ItemReadService;
import application.ItemReadService.PriceSourceUnavailableException;
import craft.PriceQuote;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.ItemRepository;

import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemReadApiControllerTest {
    private static final String ICON_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";

    @Test
    void returnsBothTradingPostBookSidesInRequestedOrder() throws Exception {
        var service = new ItemReadService(null, null, null) {
            @Override public Map<Integer, PriceQuote> livePrices(int[] ids) {
                return Map.of(19721, new PriceQuote(100, 120), 24277, new PriceQuote(200, 230));
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new ItemReadApiController(service))
                .setControllerAdvice(new ItemReadApiExceptionHandler()).build();
        mvc.perform(get("/api/items/prices").param("ids", "19721,24277"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].itemId").value(19721))
                .andExpect(jsonPath("$.prices[0].buyUnitCopper").value(100))
                .andExpect(jsonPath("$.prices[0].sellUnitCopper").value(120))
                .andExpect(jsonPath("$.prices[1].itemId").value(24277))
                .andExpect(jsonPath("$.prices[1].buyUnitCopper").value(200))
                .andExpect(jsonPath("$.prices[1].sellUnitCopper").value(230));
    }

    @Test
    void returnsApplicationIconUrlAndNullForUnknownItem() throws Exception {
        var service = new ItemReadService(null, null, null) {
            @Override public Map<Integer, ItemRepository.ItemInfo> metadata(Set<Integer> ids) {
                return Map.of(19721, new ItemRepository.ItemInfo(19721, "Glob of Ectoplasm", null, ICON_SOURCE));
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new ItemReadApiController(service))
                .setControllerAdvice(new ItemReadApiExceptionHandler()).build();
        mvc.perform(get("/api/items/metadata").param("ids", "19721,999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Glob of Ectoplasm"))
                .andExpect(jsonPath("$.items[0].iconUrl").value(
                        "/api/items/19721/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png"))
                .andExpect(jsonPath("$.items[1].itemId").value(999999))
                .andExpect(jsonPath("$.items[1].iconUrl").doesNotExist());
    }

    @Test
    void rejectsBadIdsBeforeCallingService() throws Exception {
        var service = new ItemReadService(null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(new ItemReadApiController(service))
                .setControllerAdvice(new ItemReadApiExceptionHandler()).build();
        for (String ids : new String[] {"0", "19721,19721", "1,,2", "-1", "99999999999999999999"}) {
            mvc.perform(get("/api/items/prices").param("ids", ids))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    @Test
    void reportsMissingQuoteAndUpstreamFailureWithoutInventingPrices() throws Exception {
        var missing = new ItemReadService(null, null, null) {
            @Override public Map<Integer, PriceQuote> livePrices(int[] ids) { return Map.of(); }
        };
        MockMvcBuilders.standaloneSetup(new ItemReadApiController(missing))
                .setControllerAdvice(new ItemReadApiExceptionHandler()).build()
                .perform(get("/api/items/prices").param("ids", "19721"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].buyUnitCopper").doesNotExist())
                .andExpect(jsonPath("$.prices[0].sellUnitCopper").doesNotExist());

        var unavailable = new ItemReadService(null, null, null) {
            @Override public Map<Integer, PriceQuote> livePrices(int[] ids) throws PriceSourceUnavailableException {
                throw new PriceSourceUnavailableException(new IllegalStateException());
            }
        };
        MockMvcBuilders.standaloneSetup(new ItemReadApiController(unavailable))
                .setControllerAdvice(new ItemReadApiExceptionHandler()).build()
                .perform(get("/api/items/prices").param("ids", "19721"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PRICE_SOURCE_UNAVAILABLE"));
    }
}
