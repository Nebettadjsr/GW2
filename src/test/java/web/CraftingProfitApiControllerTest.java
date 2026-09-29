package web;

import application.CraftingProfitService;
import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;
import repo.ItemRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the Crafting Profit endpoint (STORY-API-001, TEST_STRATEGY.md §11):
 * valid requests, invalid input, mapped domain/infrastructure failures, and request isolation at
 * the new boundary.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only -
 * request defaulting, validation, delegation, DTO mapping and status codes. Crafting mathematics
 * stays covered by the {@code craft.*} and {@code application.*} suites and is deliberately not
 * recomputed here.
 */
class CraftingProfitApiControllerTest {

    private static final Recipe RECIPE = new Recipe(
            7, 100, 2, 275, "Artificer", List.of(new Ingredient(200, 3)));

    /** Retained icon sources and the application URLs they must derive (STORY-API-009). */
    private static final String OUTPUT_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String OUTPUT_ICON_URL =
            "/api/items/100/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png";
    private static final String INGOT_SOURCE = "https://render.guildwars2.com/file/FEDCBA9876543210/4321.jpg";
    private static final String INGOT_ICON_URL =
            "/api/items/200/icon/9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d.jpg";

    private RecordingServiceFactory factory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        factory = new RecordingServiceFactory();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CraftingProfitApiController(factory))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---------- valid requests ----------

    @Test
    void emptyBodyRunsTheDefaultAllScopeCalculationWithTheJavaFxDefaultSettings() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(RECIPE), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("ALL"))
                .andExpect(jsonPath("$.settings.useOwnMats").value(true))
                .andExpect(jsonPath("$.settings.allowBuying").value(false))
                .andExpect(jsonPath("$.settings.maxBuyCopper").value(10000))
                .andExpect(jsonPath("$.settings.listingSell").value(false))
                .andExpect(jsonPath("$.settings.listingBuy").value(false))
                .andExpect(jsonPath("$.settings.dailyBuyInsteadOfCraft").value(true));

        StubProfitService used = factory.only();
        assertEquals(DiscChoice.Kind.ALL, used.capturedChoice.kind);
        CraftingSettings settings = used.capturedSettings;
        assertTrue(settings.useOwnMats);
        assertFalse(settings.allowBuying);
        assertEquals(10_000, settings.maxBuyCopper);
        assertFalse(settings.listingSell);
        assertFalse(settings.listingBuy);
        assertTrue(settings.dailyBuyInsteadOfCraft);
    }

    @Test
    void absentBodyIsTreatedTheSameAsAnEmptyOne() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("ALL"))
                .andExpect(jsonPath("$.rowCount").value(0));

        assertEquals(DiscChoice.Kind.ALL, factory.only().capturedChoice.kind);
    }

    @Test
    void characterDisciplineScopeAndExplicitSettingsReachTheApplicationServiceUnchanged() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scope": {"kind": "CHARACTER_DISCIPLINE", "discipline": "Chef",
                                            "characterName": "Aria", "rating": 400},
                                  "settings": {"useOwnMats": false, "allowBuying": true, "maxBuyCopper": 25000,
                                               "listingSell": true, "listingBuy": true,
                                               "dailyBuyInsteadOfCraft": false}
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("CHARACTER_DISCIPLINE"))
                .andExpect(jsonPath("$.scope.discipline").value("Chef"))
                .andExpect(jsonPath("$.scope.characterName").value("Aria"))
                .andExpect(jsonPath("$.scope.rating").value(400))
                .andExpect(jsonPath("$.settings.maxBuyCopper").value(25000));

        StubProfitService used = factory.only();
        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, used.capturedChoice.kind);
        assertEquals("Chef", used.capturedChoice.discipline);
        assertEquals("Aria", used.capturedChoice.charName);
        assertEquals(400, used.capturedChoice.rating);

        CraftingSettings settings = used.capturedSettings;
        assertFalse(settings.useOwnMats);
        assertTrue(settings.allowBuying);
        assertEquals(25_000, settings.maxBuyCopper);
        assertTrue(settings.listingSell);
        assertTrue(settings.listingBuy);
        assertFalse(settings.dailyBuyInsteadOfCraft);
    }

    @Test
    void disciplineScopeRoutesToTheDisciplineOnlyChoice() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"kind\": \"DISCIPLINE\", \"discipline\": \"Weaponsmith\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("DISCIPLINE"))
                .andExpect(jsonPath("$.scope.discipline").value("Weaponsmith"));

        assertEquals(DiscChoice.Kind.DISCIPLINE_ONLY, factory.only().capturedChoice.kind);
        assertEquals("Weaponsmith", factory.only().capturedChoice.discipline);
    }

    @Test
    void completeResultIsMappedIncludingAuthoritativeTotalsAndMissingMaterials() throws Exception {
        CraftResult result = new CraftResult(100, "Artificer", 4,
                new HashMap<>(Map.of(300, 9, 200, 5)), new HashMap<>(Map.of(200, 2)),
                1_234, 56, 7_890, 700, 2_800, null);

        factory.next(service -> service.canned = profitData(
                List.of(RECIPE),
                Map.of(RECIPE.recipeId, result),
                Map.of(100, new ItemRepository.ItemInfo(100, "Widget", null, null),
                        200, new ItemRepository.ItemInfo(200, "Ingot", null, null)),
                Map.of(100, new PriceQuote(900, 1_000), 200, new PriceQuote(10, 12))));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].recipeId").value(7))
                .andExpect(jsonPath("$.rows[0].outputItemId").value(100))
                .andExpect(jsonPath("$.rows[0].outputName").value("Widget"))
                .andExpect(jsonPath("$.rows[0].outputCount").value(2))
                .andExpect(jsonPath("$.rows[0].disciplines").value("Artificer"))
                .andExpect(jsonPath("$.rows[0].minRating").value(275))
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(true))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(4))
                .andExpect(jsonPath("$.rows[0].buyCostCopper").value(1234))
                .andExpect(jsonPath("$.rows[0].matsSellValueCopper").value(56))
                .andExpect(jsonPath("$.rows[0].revenueCopper").value(7890))
                .andExpect(jsonPath("$.rows[0].profitCopper").value(700))
                // The domain's own total, not craftableCount * profitCopper (which would be 2800
                // only by coincidence of this fixture) - STORY-APP-011 keeps it authoritative.
                .andExpect(jsonPath("$.rows[0].totalProfitCopper").value(2800))
                .andExpect(jsonPath("$.rows[0].blockedReason").value("NONE"))
                .andExpect(jsonPath("$.rows[0].outputPrice.buyUnitCopper").value(900))
                .andExpect(jsonPath("$.rows[0].outputPrice.sellUnitCopper").value(1000))
                .andExpect(jsonPath("$.rows[0].missingToBuy.length()").value(2))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].itemId").value(200))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].itemName").value("Ingot"))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].quantity").value(5))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].price.sellUnitCopper").value(12))
                // Unknown item and unquoted item are reported as such, not dropped.
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].itemId").value(300))
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].itemName").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].quantity").value(9))
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].price").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuyOne.length()").value(1))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].itemId").value(200))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].quantity").value(2));
    }

    @Test
    void aRowCarriesItsOutputItemsIconUrlAndEachMissingMaterialItsOwn() throws Exception {
        // Icons come from the retained sources the calculation's own batch item read already carried
        // (STORY-API-009): a recipe has no icon of its own, so the row uses its output item's.
        CraftResult result = new CraftResult(100, "Artificer", 4,
                new HashMap<>(Map.of(300, 9, 200, 5)), new HashMap<>(Map.of(200, 2)),
                1_234, 56, 7_890, 700, 2_800, null);

        factory.next(service -> service.canned = profitData(
                List.of(RECIPE),
                Map.of(RECIPE.recipeId, result),
                Map.of(100, new ItemRepository.ItemInfo(100, "Widget", "C:\\icons\\items\\100.png", OUTPUT_SOURCE),
                        200, new ItemRepository.ItemInfo(200, "Ingot", null, INGOT_SOURCE),
                        300, new ItemRepository.ItemInfo(300, "Scrap", null, "https://cdn.example.com/x.png")),
                Map.of()));

        String body = mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].iconUrl").value(OUTPUT_ICON_URL))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].itemId").value(200))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].iconUrl").value(INGOT_ICON_URL))
                // a rejected source and an item outside the loaded set are both simply null
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].itemId").value(300))
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].iconUrl").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].iconUrl").value(INGOT_ICON_URL))
                // the economics are untouched by icon enrichment
                .andExpect(jsonPath("$.rows[0].totalProfitCopper").value(2800))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(4))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("C:\\icons"), "no backend filesystem path may reach the browser: " + body);
        assertFalse(body.contains("render.guildwars2.com"), "no upstream URL may reach the browser: " + body);
    }

    @Test
    void anItemWithoutRetainedMetadataHasANullIconUrlAndAnUnavailableRowStillCarriesOne() throws Exception {
        factory.next(service -> service.canned = profitData(
                List.of(RECIPE),
                Map.of(),
                Map.of(100, new ItemRepository.ItemInfo(100, "Widget", null, OUTPUT_SOURCE)),
                Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                // a row whose calculation produced no result is still reported, icon included: an image
                // is display metadata and says nothing about craftability (§12.1)
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(false))
                .andExpect(jsonPath("$.rows[0].iconUrl").value(OUTPUT_ICON_URL));

        factory.next(service -> service.canned = profitData(
                List.of(RECIPE), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].outputItemId").value(100))
                .andExpect(jsonPath("$.rows[0].iconUrl").doesNotExist());
    }

    @Test
    void totalSellValueIsCopiedFromTheResultRatherThanMultipliedOutOfRevenueAndCount() throws Exception {
        // 4 crafts at 7890 revenue would be 31560. The domain says 9999, so 9999 is what the
        // response must carry - the mapper states the supplied economics, it does not check them.
        CraftResult result = new CraftResult(100, "Artificer", 4,
                Map.of(), Map.of(), 1_234, 56, 7_890, 700, 2_800, 9_999, null, BlockedReason.NONE);

        factory.next(service -> service.canned = profitData(
                List.of(RECIPE), Map.of(RECIPE.recipeId, result), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].revenueCopper").value(7890))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(4))
                .andExpect(jsonPath("$.rows[0].totalSellValueCopper").value(9999));
    }

    @Test
    void aTotalSellValueOfZeroIsReportedAsZeroAndAnAbsentResultAsAbsent() throws Exception {
        Recipe zeroValue = new Recipe(8, 101, 1, 0, "Chef", List.of());
        Recipe withoutResult = new Recipe(9, 102, 1, 0, "Chef", List.of());
        // A result exists and its sell value is a known zero; that is not the same answer as a
        // recipe the calculation produced no result for at all.
        CraftResult zeroResult = new CraftResult(101, "Chef", 0,
                Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, null, BlockedReason.PRICE_UNAVAILABLE);

        factory.next(service -> service.canned = profitData(
                List.of(zeroValue, withoutResult), Map.of(zeroValue.recipeId, zeroResult),
                Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(true))
                .andExpect(jsonPath("$.rows[0].totalSellValueCopper").value(0))
                .andExpect(jsonPath("$.rows[1].resultAvailable").value(false))
                .andExpect(jsonPath("$.rows[1].totalSellValueCopper").doesNotExist());
    }

    @Test
    void blockedAndResultlessRowsAreReportedRatherThanOmitted() throws Exception {
        Recipe blocked = new Recipe(8, 101, 1, 0, "Chef", List.of());
        Recipe withoutResult = new Recipe(9, 102, 1, 0, "Chef", List.of());
        CraftResult blockedResult = new CraftResult(101, "Chef", 0,
                Map.of(), Map.of(), 0, 0, 0, 0, 0, null, BlockedReason.PRICE_UNAVAILABLE);

        factory.next(service -> service.canned = profitData(
                List.of(blocked, withoutResult),
                Map.of(blocked.recipeId, blockedResult),
                Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.rows[0].recipeId").value(8))
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(true))
                .andExpect(jsonPath("$.rows[0].blockedReason").value("PRICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(0))
                .andExpect(jsonPath("$.rows[1].recipeId").value(9))
                .andExpect(jsonPath("$.rows[1].resultAvailable").value(false))
                .andExpect(jsonPath("$.rows[1].totalProfitCopper").doesNotExist())
                .andExpect(jsonPath("$.rows[1].blockedReason").doesNotExist());
    }

    @Test
    void scopeThatMatchesNoRecipesIsAnEmptySuccessNotAMissingResource() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": {"kind": "CHARACTER_DISCIPLINE", "discipline": "Chef",
                                           "characterName": "NoSuchCharacter"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    // ---------- invalid input ----------

    @Test
    void unknownScopeKindIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"kind\": \"EVERYTHING\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        assertEquals(0, factory.created.size(), "an invalid request must not create or call the service");
    }

    @Test
    void disciplineScopeWithoutADisciplineIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"kind\": \"DISCIPLINE\", \"discipline\": \"  \"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("scope.discipline is required for scope.kind DISCIPLINE"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void characterDisciplineScopeWithoutACharacterNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"kind\": \"CHARACTER_DISCIPLINE\", \"discipline\": \"Chef\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("scope.characterName is required for scope.kind CHARACTER_DISCIPLINE"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void negativeMaxBuyCopperIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": {\"maxBuyCopper\": -1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("settings.maxBuyCopper must not be negative"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void malformedJsonIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void unknownPathUnderTheApiIsANotFound() throws Exception {
        mockMvc.perform(get("/api/crafting/profit/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, factory.created.size());
    }

    // ---------- mapped failures ----------

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        factory.next(service -> service.failure =
                new SQLException("connection to jdbc:postgresql://host/db refused"));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"));
    }

    @Test
    void craftingGraphCacheFailureIsMappedToInternalServerError() throws Exception {
        factory.next(service -> service.failure =
                new RuntimeException("Failed to load crafting graph cache"));

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("CALCULATION_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("The crafting calculation could not be completed"));
    }

    // ---------- request isolation ----------

    @Test
    void successiveRequestsEachGetTheirOwnServiceAndTheirOwnResult() throws Exception {
        factory.deriveResultFromRequest = true;

        mockMvc.perform(profitRequestForDiscipline(11))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(11))
                .andExpect(jsonPath("$.rows[0].outputName").value("D11"));

        mockMvc.perform(profitRequestForDiscipline(22))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(22))
                .andExpect(jsonPath("$.rows[0].outputName").value("D22"));

        assertEquals(2, factory.created.size());
        assertNotSame(factory.created.get(0), factory.created.get(1),
                "each request must run on its own service instance, so no reload state is shared");
    }

    @Test
    void concurrentRequestsWithDistinctInputsDoNotMixResults() throws Exception {
        factory.deriveResultFromRequest = true;
        int requests = 8;

        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int discipline = 100 + i;
            calls.add(() -> mockMvc.perform(profitRequestForDiscipline(discipline))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].recipeId").value(discipline))
                    .andExpect(jsonPath("$.rows[0].outputName").value("D" + discipline))
                    .andExpect(jsonPath("$.scope.discipline").value("D" + discipline))
                    .andReturn().getResponse().getContentAsString());
        }

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<String>> futures = pool.invokeAll(calls);
            for (Future<String> future : futures) {
                assertNotNull(future.get(), "every concurrent request must answer with its own result");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(requests, factory.created.size(), "one service instance per request");
        assertEquals(requests, distinctInstances(factory.created),
                "concurrent requests must not share a service instance");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            profitRequestForDiscipline(int discipline) {
        return post("/api/crafting/profit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\": {\"kind\": \"DISCIPLINE\", \"discipline\": \"D" + discipline + "\"}}");
    }

    private static int distinctInstances(List<StubProfitService> instances) {
        Map<StubProfitService, Boolean> identity = new java.util.IdentityHashMap<>();
        for (StubProfitService instance : instances) identity.put(instance, Boolean.TRUE);
        return identity.size();
    }

    // ---------- stubs ----------

    private static CraftingProfitService.ProfitData profitData(List<Recipe> visible,
                                                               Map<Integer, CraftResult> results,
                                                               Map<Integer, ItemRepository.ItemInfo> items,
                                                               Map<Integer, PriceQuote> tp) {
        return new CraftingProfitService.ProfitData(visible, visible, results, items, tp);
    }

    /**
     * Stands in for the application service: records what the controller handed it and returns
     * canned data (or fails), so these tests never touch a database or a real calculation.
     */
    private static final class StubProfitService extends CraftingProfitService {
        DiscChoice capturedChoice;
        CraftingSettings capturedSettings;
        ProfitData canned = new ProfitData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        Exception failure;
        boolean deriveResultFromRequest;

        StubProfitService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public ProfitData reload(DiscChoice choice, CraftingSettings settings) throws SQLException {
            this.capturedChoice = choice;
            this.capturedSettings = settings;

            if (failure instanceof SQLException sqlException) throw sqlException;
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;

            if (!deriveResultFromRequest) return canned;

            // Result derived from this request's own input, so a response that carried another
            // request's data would be visible as a mismatched recipe id / output name.
            int recipeId = Integer.parseInt(choice.discipline.substring(1));
            Recipe recipe = new Recipe(recipeId, recipeId, 1, 0, choice.discipline, List.of());
            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ProfitData(List.of(recipe), List.of(recipe), Map.of(),
                    Map.of(recipeId, new ItemRepository.ItemInfo(recipeId, choice.discipline, null, null)),
                    Map.of());
        }
    }

    /** The controller's per-request service seam, recording every instance it hands out. */
    private static final class RecordingServiceFactory implements Supplier<CraftingProfitService> {
        final List<StubProfitService> created = Collections.synchronizedList(new ArrayList<>());
        private java.util.function.Consumer<StubProfitService> configure = service -> {};
        boolean deriveResultFromRequest;

        void next(java.util.function.Consumer<StubProfitService> configure) {
            this.configure = configure;
        }

        StubProfitService only() {
            assertEquals(1, created.size(), "expected exactly one service instance");
            return created.get(0);
        }

        /** The most recently handed-out service, for a test that performs more than one request. */
        StubProfitService last() {
            assertFalse(created.isEmpty(), "expected at least one service instance");
            return created.get(created.size() - 1);
        }

        @Override
        public CraftingProfitService get() {
            StubProfitService service = new StubProfitService();
            service.deriveResultFromRequest = deriveResultFromRequest;
            configure.accept(service);
            created.add(service);
            return service;
        }
    }
}
