package web;

import application.CachedCraftingProfitService;
import application.CraftingProfitService;
import application.CraftingResolutionDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.Recipe;
import craft.SingleCraftExplanation;
import model.AccountScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #235: raw HTTP input exercises Jackson coercion before canonical settings reach the service. */
class MaterialReservationApiContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RecordingService service = new RecordingService();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CraftingProfitApiController(service,
                        new web.access.AccountAccessHttp("")))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    static Stream<Object[]> routesAndFields() {
        return Stream.of(false, true).flatMap(detail -> Stream.of("protectedBankQuantities",
                "protectedMaterialQuantities").map(field -> new Object[]{detail, field}));
    }

    @ParameterizedTest
    @MethodSource("routesAndFields")
    void fractionalJsonQuantitiesAreRejectedWithoutTruncatingOrCalculating(boolean detail, String field)
            throws Exception {
        for (String value : List.of("1.5", "-0.5", "1.0000000001")) {
            mvc.perform(request(detail, "{\"" + field + "\":{\"200\":" + value + "}}"))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(service.calls.isEmpty(), "fractional values must never reach calculation");
    }

    @ParameterizedTest
    @MethodSource("routesAndFields")
    void invalidQuantityMapsAreRejectedBeforeCalculation(boolean detail, String field) throws Exception {
        for (String map : List.of("{\"200\":-1}", "{\"200\":2147483648}", "{\"200\":1e309}",
                "{\"200\":\"NaN\"}", "{\"200\":null}", "{\"200\":true}", "{\"0\":250}",
                "{\"-1\":250}", "{\"1.5\":250}", "{\"not-an-id\":250}")) {
            mvc.perform(request(detail, "{\"" + field + "\":" + map + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(service.calls.isEmpty(), "invalid maps must not allocate a calculation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finiteMapsAreEchoedAndPassedIndependentlyWithoutAGlobalMask(boolean detail) throws Exception {
        JsonNode settings = echoed(detail, """
                {"materialProtectionEnabled":true,"protectedItemIds":[],
                 "protectedBankQuantities":{"200":250,"201":3000},
                 "protectedMaterialQuantities":{"200":25}}
                """);
        assertEquals(JSON.readTree("{\"200\":250,\"201\":3000}"), settings.get("protectedBankQuantities"));
        assertEquals(JSON.readTree("{\"200\":25}"), settings.get("protectedMaterialQuantities"));
        assertTrue(settings.get("protectedItemIds").isEmpty());
        CraftingSettings captured = service.calls.getFirst();
        assertTrue(captured.protectedItemIds.isEmpty());
        assertEquals(Map.of(200, 250, 201, 3000), quantities(captured, "protectedBankQuantities"));
        assertEquals(Map.of(200, 25), quantities(captured, "protectedMaterialQuantities"));
        assertEquals(quantities(captured, "protectedBankQuantities"),
                quantities(captured.withBuyingDisabled(), "protectedBankQuantities"));
        assertEquals(quantities(captured, "protectedMaterialQuantities"),
                quantities(captured.withBuyingDisabled(), "protectedMaterialQuantities"));
        assertThrows(UnsupportedOperationException.class,
                () -> quantities(captured, "protectedBankQuantities").put(202, 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroClearsAndSourceAllTakesPrecedenceWithoutCappingTheSavedTarget(boolean detail) throws Exception {
        JsonNode settings = echoed(detail, """
                {"materialProtectionEnabled":true,"protectedItemIds":[],
                 "protectedBankItemIds":[200,200],
                 "protectedBankQuantities":{"200":250,"201":0,"202":2147483647},
                 "protectedMaterialQuantities":{"200":250}}
                """);
        assertEquals(JSON.readTree("[200]"), settings.get("protectedBankItemIds"));
        assertEquals(JSON.readTree("{\"202\":2147483647}"), settings.get("protectedBankQuantities"));
        assertEquals(JSON.readTree("{\"200\":250}"), settings.get("protectedMaterialQuantities"));
        assertEquals(Map.of(202, Integer.MAX_VALUE), quantities(service.calls.getFirst(), "protectedBankQuantities"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedMapsPreserveLegacyAllSelectionsAndDefaultToEmpty(boolean detail) throws Exception {
        JsonNode settings = echoed(detail, """
                {"materialProtectionEnabled":true,"protectedItemIds":[201],
                 "protectedBankItemIds":[200],"protectedMaterialItemIds":[202]}
                """);
        assertEquals(JSON.readTree("{}"), settings.get("protectedBankQuantities"));
        assertEquals(JSON.readTree("{}"), settings.get("protectedMaterialQuantities"));
        assertTrue(service.calls.getFirst().isProtected(201));
        assertEquals(java.util.Set.of(200), service.calls.getFirst().protectedBankItemIds);
        assertEquals(java.util.Set.of(202), service.calls.getFirst().protectedMaterialItemIds);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingOnlyAQuantityChangesTheEchoAndTheEffectiveSettings(boolean detail) throws Exception {
        for (int quantity : List.of(250, 251)) {
            JsonNode settings = echoed(detail, "{\"materialProtectionEnabled\":true,\"protectedItemIds\":[],"
                    + "\"protectedBankQuantities\":{\"200\":" + quantity + "}}");
            assertNotNull(settings.get("protectedBankQuantities"));
            assertEquals(quantity, settings.get("protectedBankQuantities").get("200").asInt());
        }
        assertEquals(Map.of(200, 250), quantities(service.calls.get(0), "protectedBankQuantities"));
        assertEquals(Map.of(200, 251), quantities(service.calls.get(1), "protectedBankQuantities"));
    }

    private JsonNode echoed(boolean detail, String settings) throws Exception {
        String body = mvc.perform(request(detail, settings)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = JSON.readTree(body);
        return detail ? response.get("calculation").get("settings") : response.get("settings");
    }

    private static MockHttpServletRequestBuilder request(boolean detail, String settings) {
        String calculation = "{\"settings\":" + settings + "}";
        return post(detail ? "/api/crafting/profit/resolution" : "/api/crafting/profit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(detail ? "{\"recipeId\":7,\"calculation\":" + calculation + "}" : calculation);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> quantities(CraftingSettings settings, String field) throws Exception {
        try {
            return (Map<Integer, Integer>) settings.getClass().getField(field).get(settings);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("effective settings must carry " + field, missing);
        }
    }

    private static final class RecordingService extends CachedCraftingProfitService {
        private final List<CraftingSettings> calls = new ArrayList<>();
        private RecordingService() { super(null, null, null, null, null, null, null, null, null); }

        @Override
        public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings) {
            calls.add(settings);
            return new ProfitOutcome(new CraftingProfitService.ProfitData(List.of(), List.of(),
                    Map.of(), Map.of(), Map.of()), null);
        }

        @Override
        public ResolutionOutcome resolution(AccountScope scope, int recipeId, DiscChoice choice,
                                             CraftingSettings settings, String basedOnCalculationId) {
            calls.add(settings);
            Recipe recipe = new Recipe(7, 100, 1, 0, "Artificer", List.of(new Ingredient(200, 1)));
            CraftResult row = new CraftResult(100, "Artificer", 0, Map.of(), Map.of(), 0, 0, 0, 0, 0, null);
            var detail = new CraftingResolutionDetail(7, CraftingResolutionDetail.Status.RESULT_UNAVAILABLE,
                    recipe, row, SingleCraftExplanation.unavailable(7, 100, 1), Map.of(), Map.of());
            return new ResolutionOutcome(detail, CONSISTENCY_FRESH_CALCULATION, null);
        }
    }
}
