package web;

import application.EctoSalvageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ecto.EctoSalvageCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tradingpost.TradingPostFeePolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the Ectoplasm Salvage route (STORY-WEB-013, TEST_STRATEGY.md §11):
 * exactly-once delegation, every supplied scenario value preserved, the unavailable result kept
 * apart from zero, rejected input that never reaches the service, sanitized failures and request
 * isolation.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only. The
 * fee and yield arithmetic stays where it belongs - {@code ecto.EctoSalvageCalculatorTest} owns it -
 * and no expected value here is recomputed from a supplied quote, which is what lets a mapper that
 * "helpfully" derived one fail these tests instead of agreeing with itself.
 */
class EctoSalvageApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Deliberately incoherent numbers: no field is the arithmetic consequence of another, so a
     * boundary that recomputed profit, net cost, the recovered Dust value or the Luck cost from the
     * quotes beside them would produce something else and fail.
     */
    private static final EctoSalvageCalculator.ScenarioResult INSTANT_INSTANT =
            new EctoSalvageCalculator.ScenarioResult(1_111, 2_222, 3_333, 4_444, 5_555, -6_666, 7_777);
    private static final EctoSalvageCalculator.ScenarioResult INSTANT_LISTING =
            new EctoSalvageCalculator.ScenarioResult(1_212, 2_323, 3_434, 4_545, -5_656, 6_767, -7_878);
    private static final EctoSalvageCalculator.ScenarioResult LISTING_INSTANT =
            new EctoSalvageCalculator.ScenarioResult(1_313, 2_424, 0, 4_646, 5_757, 0, 7_979);
    private static final EctoSalvageCalculator.ScenarioResult LISTING_LISTING =
            new EctoSalvageCalculator.ScenarioResult(1_414, 2_525, 3_636, 4_747, 5_858, 6_969, 8_080);

    private static final EctoSalvageService.EctoScenarios ALL_FOUR = new EctoSalvageService.EctoScenarios(
            INSTANT_INSTANT, INSTANT_LISTING, LISTING_INSTANT, LISTING_LISTING);

    private MockMvc mockMvcFor(StubEctoSalvageService service) {
        return MockMvcBuilders
                .standaloneSetup(new EctoSalvageApiController(service))
                .setControllerAdvice(new EctoSalvageApiExceptionHandler())
                .build();
    }

    // ---------- successful calculations ----------

    @Test
    void returnsAllFourScenariosExactlyAsTheServiceReportedThemAfterOneDelegation() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.resultAvailable").value(true))
                .andExpect(jsonPath("$.ectoItemId").value(EctoSalvageService.ECTO_ID))
                .andExpect(jsonPath("$.dustItemId").value(EctoSalvageService.DUST_ID))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = JSON.readTree(body);
        assertScenario(response.get("instantBuyInstantSell"), INSTANT_INSTANT);
        assertScenario(response.get("instantBuyListingSell"), INSTANT_LISTING);
        assertScenario(response.get("listingBuyInstantSell"), LISTING_INSTANT);
        assertScenario(response.get("listingBuyListingSell"), LISTING_LISTING);

        assertEquals(1, service.callCount.get(), "one accepted request is one delegation, not two");
    }

    @Test
    void keepsEachScenarioInItsOwnFieldRatherThanReorderingThem() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                // The service's own buy/sell combination naming, unchanged: the Ecto acquisition
                // cost of the two instant-buy scenarios differs from the two listing-buy ones.
                .andExpect(jsonPath("$.instantBuyInstantSell.ectoAcquisitionCostCopper").value(1_111))
                .andExpect(jsonPath("$.instantBuyListingSell.ectoAcquisitionCostCopper").value(1_212))
                .andExpect(jsonPath("$.listingBuyInstantSell.ectoAcquisitionCostCopper").value(1_313))
                .andExpect(jsonPath("$.listingBuyListingSell.ectoAcquisitionCostCopper").value(1_414));
    }

    @Test
    void preservesNegativeValuesAndASuppliedZeroRatherThanNormalizingThem() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instantBuyInstantSell.profitPerEctoCopper").value(-6_666))
                .andExpect(jsonPath("$.instantBuyListingSell.netCostPerEctoCopper").value(-5_656))
                .andExpect(jsonPath("$.instantBuyListingSell.costPer1000LuckCopper").value(-7_878))
                .andReturn().getResponse().getContentAsString();

        JsonNode brokeEven = JSON.readTree(body).get("listingBuyInstantSell");
        assertTrue(brokeEven.get("profitPerEctoCopper").isNumber(), "a supplied zero is a value");
        assertEquals(0, brokeEven.get("profitPerEctoCopper").asInt());
        assertEquals(0, brokeEven.get("expectedGrossRecoveredDustValueCopper").asInt());
    }

    @Test
    void reportsTheDomainsExpectedValueAssumptionsAndFeePercentage() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assumptions.expectedLuckPerEcto")
                        .value(EctoSalvageCalculator.LUCK_PER_ECTO))
                .andExpect(jsonPath("$.assumptions.expectedDustPerEcto")
                        .value(EctoSalvageCalculator.DUST_PER_ECTO))
                .andExpect(jsonPath("$.assumptions.ectosPer1000Luck")
                        .value(EctoSalvageCalculator.ECTOS_PER_1000_LUCK))
                .andExpect(jsonPath("$.assumptions.tradingPostSellFeePercent")
                        .value(TradingPostFeePolicy.PROFIT_FEE_PERCENT));
    }

    @Test
    void anUnavailableResultIsACompletedCalculationWithNullScenariosNotZeroedOnes() throws Exception {
        StubEctoSalvageService service =
                new StubEctoSalvageService(EctoSalvageService.EctoScenarios.UNAVAILABLE);

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultAvailable").value(false))
                .andExpect(jsonPath("$.instantBuyInstantSell").doesNotExist())
                .andExpect(jsonPath("$.instantBuyListingSell").doesNotExist())
                .andExpect(jsonPath("$.listingBuyInstantSell").doesNotExist())
                .andExpect(jsonPath("$.listingBuyListingSell").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = JSON.readTree(body);
        for (String scenario : List.of(
                "instantBuyInstantSell", "instantBuyListingSell",
                "listingBuyInstantSell", "listingBuyListingSell")) {
            assertTrue(response.get(scenario).isNull(),
                    scenario + " must be null when no usable quotes were returned, never a zero scenario");
        }
        // The assumptions do not depend on a price snapshot, so they are still reported.
        assertEquals(TradingPostFeePolicy.PROFIT_FEE_PERCENT,
                response.get("assumptions").get("tradingPostSellFeePercent").asInt());
        assertEquals(1, service.callCount.get());
    }

    @Test
    void aScenarioCarriesOnlyTheDocumentedTransportFields() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> fields = new ArrayList<>();
        JSON.readTree(body).get("instantBuyInstantSell").fieldNames().forEachRemaining(fields::add);

        assertEquals(List.of(
                        "ectoAcquisitionCostCopper",
                        "dustGrossUnitPriceCopper",
                        "expectedGrossRecoveredDustValueCopper",
                        "netValueOfRecoveredDustCopper",
                        "netCostPerEctoCopper",
                        "profitPerEctoCopper",
                        "costPer1000LuckCopper"),
                fields,
                "the scenario must be a transport record, not a serialized domain object");
        assertFalse(body.contains("guildwars2.com"),
                "no upstream address may reach the browser: " + body);
    }

    // ---------- rejected input ----------

    @Test
    void aQueryParameterIsRejectedBeforeAnyCalculationRuns() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        mockMvcFor(service).perform(get("/api/ecto/salvage").param("dustPerEcto", "1.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("The Ectoplasm salvage calculation takes no parameters; remove them from the request"));

        assertEquals(0, service.callCount.get(), "a rejected request must not reach the service");
    }

    @Test
    void postingToTheRouteIsNotAllowedAndCalculatesNothing() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        mockMvcFor(service).perform(post("/api/ecto/salvage"))
                .andExpect(status().isMethodNotAllowed());

        assertEquals(0, service.callCount.get());
    }

    @Test
    void unknownPathUnderTheRouteIsANotFound() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(ALL_FOUR);

        mockMvcFor(service).perform(get("/api/ecto/salvage/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, service.callCount.get());
    }

    // ---------- mapped failures ----------

    @Test
    void aLivePriceFetchFailureIsMappedToBadGatewayWithoutLeakingDetail() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(
                new IOException("TP price fetch failed: HTTP 503 from https://api.guildwars2.com/v2/commerce/prices"));

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PRICE_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("Live Trading Post prices for the Ectoplasm calculation are currently unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("api.guildwars2.com"), "the response must not carry the upstream URL: " + body);
        assertFalse(body.contains("IOException"), "the response must not carry exception text: " + body);
        assertFalse(body.contains("HTTP 503"), "the response must not carry the upstream status: " + body);
    }

    @Test
    void anyOtherCalculationFailureIsMappedTheSameWay() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService(
                new IllegalStateException("Unexpected TP JSON"));

        String body = mockMvcFor(service).perform(get("/api/ecto/salvage"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PRICE_SOURCE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("Unexpected TP JSON"), "the response must not carry the cause: " + body);
    }

    // ---------- request isolation ----------

    @Test
    void concurrentRequestsEachAnswerWithTheirOwnCalculation() throws Exception {
        StubEctoSalvageService service = new StubEctoSalvageService();
        MockMvc mockMvc = mockMvcFor(service);
        int requests = 8;

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            calls.add(() -> {
                String body = mockMvc.perform(get("/api/ecto/salvage"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                return distinctCalculationMarker(JSON.readTree(body));
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Integer> markers = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(calls)) {
                Integer marker = future.get();
                assertNotNull(marker, "every concurrent request must answer with one coherent result");
                markers.add(marker);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(requests, service.callCount.get(), "one delegation per request");
        assertEquals(requests, markers.stream().distinct().count(),
                "no response may carry another request's calculation: " + markers);
    }

    /**
     * Every scenario of one stubbed calculation carries the same marker, and each call gets a new
     * one. A response mixing two calculations therefore has disagreeing scenarios, which fails here
     * rather than being averaged away.
     */
    private static Integer distinctCalculationMarker(JsonNode response) {
        int marker = response.get("instantBuyInstantSell").get("ectoAcquisitionCostCopper").asInt();
        for (String scenario : List.of(
                "instantBuyListingSell", "listingBuyInstantSell", "listingBuyListingSell")) {
            assertEquals(marker, response.get(scenario).get("ectoAcquisitionCostCopper").asInt(),
                    "one response must report one calculation's scenarios");
        }
        return marker;
    }

    // ---------- assertions ----------

    private static void assertScenario(JsonNode actual, EctoSalvageCalculator.ScenarioResult expected) {
        assertNotNull(actual, "the scenario must be present");
        assertEquals(expected.ectoAcquisitionCost(), actual.get("ectoAcquisitionCostCopper").asInt());
        assertEquals(expected.dustGrossUnitPrice(), actual.get("dustGrossUnitPriceCopper").asInt());
        assertEquals(expected.expectedGrossRecoveredDustValue(), actual.get("expectedGrossRecoveredDustValueCopper").asInt());
        assertEquals(expected.netValueOfRecoveredDust(), actual.get("netValueOfRecoveredDustCopper").asInt());
        assertEquals(expected.netCostPerEcto(), actual.get("netCostPerEctoCopper").asInt());
        assertEquals(expected.profitPerEcto(), actual.get("profitPerEctoCopper").asInt());
        assertEquals(expected.costPer1000Luck(), actual.get("costPer1000LuckCopper").asInt());
    }

    // ---------- stub ----------

    /**
     * Stands in for the real application service. Subclassing (rather than an interface) keeps the
     * production controller depending on the same concrete type the JavaFX Ecto view uses, which is
     * what the story's "neither client acquires a second economic implementation" constraint asks
     * for. It performs no fetch and no calculation of its own.
     */
    private static final class StubEctoSalvageService extends EctoSalvageService {
        private final EctoSalvageService.EctoScenarios canned;
        private final Exception failure;
        /** When true, each call answers with its own distinguishable calculation. */
        private final boolean distinctPerCall;
        final AtomicInteger callCount = new AtomicInteger();

        StubEctoSalvageService(EctoSalvageService.EctoScenarios canned) {
            this(canned, null, false);
        }

        StubEctoSalvageService(Exception failure) {
            this(null, failure, false);
        }

        /** Answers every call with a different calculation, for the request-isolation check. */
        StubEctoSalvageService() {
            this(null, null, true);
        }

        private StubEctoSalvageService(EctoSalvageService.EctoScenarios canned,
                                       Exception failure,
                                       boolean distinctPerCall) {
            this.canned = canned;
            this.failure = failure;
            this.distinctPerCall = distinctPerCall;
        }

        @Override
        public EctoScenarios calculate() throws Exception {
            int call = callCount.incrementAndGet();
            if (failure != null) throw failure;
            if (!distinctPerCall) return canned;

            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int marker = 10_000 * call;
            return new EctoScenarios(
                    markedScenario(marker), markedScenario(marker),
                    markedScenario(marker), markedScenario(marker));
        }

        private static EctoSalvageCalculator.ScenarioResult markedScenario(int marker) {
            return new EctoSalvageCalculator.ScenarioResult(marker, 1, 1, 1, 1, 1, 1);
        }
    }
}
