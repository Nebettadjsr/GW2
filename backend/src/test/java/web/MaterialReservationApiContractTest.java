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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeManualBankAllocationsAreSortedEchoedAndPreservedWithoutBuying(boolean detail) throws Exception {
        String input = "[" + allocation(9, 200, 250, 250, "Character", "Aria") + ","
                + allocation(0, 200, 0, 250, null, null) + ","
                + allocation(4, 200, 0, 250, "Unrecognized binding", "") + ","
                + allocation(2, 201, 10, 10, null, null) + "]";
        JsonNode expected = JSON.readTree("[" + allocation(0, 200, 0, 250, null, null) + ","
                + allocation(2, 201, 10, 10, null, null) + ","
                + allocation(4, 200, 0, 250, "Unrecognized binding", "") + ","
                + allocation(9, 200, 250, 250, "Character", "Aria") + "]");

        JsonNode settings = echoed(detail, bankSettings("{\"200\":250,\"201\":10}", input));

        assertEquals(expected, settings.get("protectedBankAllocations"));
        assertTrue(settings.get("protectedItemIds").isEmpty());
        CraftingSettings captured = service.calls.getFirst();
        assertEquals(expected, JSON.valueToTree(allocations(captured)));
        assertEquals(expected, JSON.valueToTree(allocations(captured.withBuyingDisabled())));
        assertThrows(UnsupportedOperationException.class, () -> allocations(captured).clear());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedNullableGuardsAndLegacyAllocationShapesNormalizeWithoutInventingBindingRules(boolean detail)
            throws Exception {
        for (String value : List.of("", ",\"protectedBankAllocations\":null",
                ",\"protectedBankAllocations\":[]")) {
            assertEquals(JSON.readTree("[]"), echoed(detail,
                    "{\"materialProtectionEnabled\":true" + value + "}").get("protectedBankAllocations"));
        }
        for (String guards : List.of("", ",\"binding\":null,\"boundTo\":null",
                ",\"binding\":\"\",\"boundTo\":\"\"",
                ",\"binding\":\"Character\",\"boundTo\":null")) {
            JsonNode actual = echoed(detail, bankSettings("{\"200\":250}",
                    "[{\"slot\":0,\"itemId\":200,\"reservedUnits\":250,\"observedCount\":250"
                            + guards + "}]")).get("protectedBankAllocations").get(0);
            assertTrue(actual.has("binding") && actual.has("boundTo"));
            assertEquals(JSON.readTree("{" + (guards.isEmpty() ? "\"binding\":null,\"boundTo\":null"
                    : guards.substring(1)) + "}").get("binding"), actual.get("binding"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allocationObservationTotalsUseWideArithmeticAndKeepTargetsAboveStock(boolean detail) throws Exception {
        String twoHugeStacks = "[" + allocation(0, 200, Integer.MAX_VALUE, Integer.MAX_VALUE, null, null)
                + "," + allocation(1, 200, 0, Integer.MAX_VALUE, null, null) + "]";
        JsonNode wide = echoed(detail, bankSettings("{\"200\":2147483647}", twoHugeStacks));
        assertEquals(JSON.readTree(twoHugeStacks), wide.get("protectedBankAllocations"));
        JsonNode belowTarget = echoed(detail, bankSettings("{\"200\":300}",
                "[" + allocation(0, 200, 250, 250, null, null) + "]"));
        assertEquals(300, belowTarget.get("protectedBankQuantities").get("200").asInt());
        assertEquals(250, belowTarget.get("protectedBankAllocations").get(0).get("reservedUnits").asInt());
    }

    static Stream<Object[]> dominantBankAllRequests() {
        return Stream.of(false, true).flatMap(detail -> Stream.of("protectedItemIds", "protectedBankItemIds")
                .map(field -> new Object[]{detail, field}));
    }

    @ParameterizedTest
    @MethodSource("dominantBankAllRequests")
    void legacyAllDropsItsBankQuantityAndHintsBeforeRemainingGroupValidation(boolean detail, String allField)
            throws Exception {
        String input = "{\"materialProtectionEnabled\":true,\"" + allField + "\":[200],"
                + "\"protectedBankQuantities\":{\"200\":250},\"protectedMaterialQuantities\":{\"201\":25},"
                + "\"protectedBankAllocations\":[" + allocation(0, 200, 1, 250, null, null) + "]}";

        JsonNode settings = echoed(detail, input);

        assertEquals(JSON.readTree("{}"), settings.get("protectedBankQuantities"));
        assertEquals(JSON.readTree("[]"), settings.get("protectedBankAllocations"));
        assertEquals(JSON.readTree("{\"201\":25}"), settings.get("protectedMaterialQuantities"));
        assertTrue(allocations(service.calls.getFirst()).isEmpty());
    }

    static Stream<Object[]> invalidBankAllocationRequests() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        String valid = allocation(0, 200, 250, 250, null, null);
        for (String shape : List.of("{}", "true", "1", "\"not-an-array\"", "[null]")) {
            cases.add(new Object[]{"allocation shape " + shape, bankSettings("{\"200\":250}", shape)});
        }
        for (String required : List.of("slot", "itemId", "reservedUnits", "observedCount")) {
            var missing = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(valid);
            missing.remove(required);
            cases.add(new Object[]{"missing " + required, bankSettings("{\"200\":250}", "[" + missing + "]")});
        }
        Map<String, List<String>> badFields = Map.of(
                "slot", List.of("-1", "0.5", "2147483648", "\"0\"", "null", "true"),
                "itemId", List.of("0", "-1", "200.5", "2147483648", "\"200\""),
                "reservedUnits", List.of("-1", "250.5", "251", "2147483648", "\"250\"", "null"),
                "observedCount", List.of("0", "-1", "250.5", "2147483648", "\"250\""),
                "binding", List.of("1", "true", "{}", "[]"),
                "boundTo", List.of("1", "true", "{}", "[]"));
        for (var field : badFields.entrySet()) {
            for (String raw : field.getValue()) {
                var bad = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(valid);
                bad.set(field.getKey(), JSON.readTree(raw));
                cases.add(new Object[]{field.getKey() + "=" + raw,
                        bankSettings("{\"200\":250}", "[" + bad + "]")});
            }
        }
        cases.add(new Object[]{"duplicate slot", bankSettings("{\"200\":250}", "[" + valid + "," + valid + "]")});
        cases.add(new Object[]{"orphan group", bankSettings("{}", "[" + valid + "]")});
        cases.add(new Object[]{"zero target orphan", bankSettings("{\"200\":0}", "[" + valid + "]")});
        cases.add(new Object[]{"wrong reserved sum", bankSettings("{\"200\":249}", "[" + valid + "]")});
        cases.add(new Object[]{"ALL cannot hide bad shape", "{\"protectedBankItemIds\":[200],"
                + "\"protectedBankAllocations\":[{\"slot\":-1,\"itemId\":200,"
                + "\"reservedUnits\":250,\"observedCount\":250}]}"});
        return cases.stream().flatMap(c -> Stream.of(false, true)
                .map(detail -> new Object[]{c[0], detail, c[1]}));
    }

    @ParameterizedTest(name = "{0}, detail={1}")
    @MethodSource("invalidBankAllocationRequests")
    void invalidAllocationJsonIsRejectedBeforeAnyCalculation(String label, boolean detail, String settings)
            throws Exception {
        mvc.perform(request(detail, settings)).andExpect(status().isBadRequest());
        assertTrue(service.calls.isEmpty(), label + " must not reach calculation");
    }

    private static String bankSettings(String quantities, String allocations) {
        return "{\"materialProtectionEnabled\":true,\"protectedItemIds\":[],\"protectedBankQuantities\":"
                + quantities + ",\"protectedBankAllocations\":" + allocations + "}";
    }

    private static String allocation(int slot, int itemId, int reserved, int observed, String binding, String owner)
            throws Exception {
        var row = JSON.createObjectNode().put("slot", slot).put("itemId", itemId)
                .put("reservedUnits", reserved).put("observedCount", observed);
        row.put("binding", binding);
        row.put("boundTo", owner);
        return JSON.writeValueAsString(row);
    }

    private static List<?> allocations(CraftingSettings settings) throws Exception {
        try {
            return (List<?>) settings.getClass().getField("protectedBankAllocations").get(settings);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("effective settings must carry protectedBankAllocations", missing);
        }
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
        private RecordingService() { super(null, null, null, null, null, null, null, null); }

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
