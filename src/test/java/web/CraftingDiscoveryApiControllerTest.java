package web;

import application.CraftingDiscoveryService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;
import repo.ItemRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the Crafting Discovery endpoint (STORY-API-002, TEST_STRATEGY.md §11 and
 * §35.1): valid requests, invalid input, the empty result, mapped domain/infrastructure failures,
 * and request isolation at this boundary.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only -
 * request defaulting, validation, delegation, DTO mapping and status codes. Discovery filtering,
 * recipe eligibility and crafting mathematics stay covered by the {@code craft.*} and
 * {@code application.*} suites and are deliberately not recomputed here.
 */
class CraftingDiscoveryApiControllerTest {

    private static final Recipe RECIPE = new Recipe(
            7, 100, 2, 275, "Chef", List.of(new Ingredient(200, 3)));

    /** A complete, valid scope; individual tests override the parts they are about. */
    private static final String FULL_SCOPE =
            "\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"Aria\", \"rating\": 400}";

    private RecordingServiceFactory factory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        factory = new RecordingServiceFactory();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CraftingDiscoveryApiController(factory))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---------- valid requests ----------

    @Test
    void scopeInventoryCharacterAndExplicitSettingsReachTheApplicationServiceUnchanged() throws Exception {
        factory.next(service -> service.canned = discoveryData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scope": {"discipline": "Chef", "characterName": "Aria", "rating": 400},
                                  "inventoryCharacterName": "Borin",
                                  "settings": {"useOwnMats": false, "allowBuying": false, "maxBuyCopper": 25000,
                                               "listingSell": true, "listingBuy": true}
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.discipline").value("Chef"))
                .andExpect(jsonPath("$.scope.characterName").value("Aria"))
                .andExpect(jsonPath("$.scope.rating").value(400))
                .andExpect(jsonPath("$.inventoryCharacterName").value("Borin"))
                .andExpect(jsonPath("$.settings.useOwnMats").value(false))
                .andExpect(jsonPath("$.settings.allowBuying").value(false))
                .andExpect(jsonPath("$.settings.maxBuyCopper").value(25000))
                .andExpect(jsonPath("$.settings.listingSell").value(true))
                .andExpect(jsonPath("$.settings.listingBuy").value(true));

        StubDiscoveryService used = factory.only();
        // Discovery is individual-character only: always the CHAR_DISCIPLINE choice the JavaFX
        // selector produces, never an All or discipline-only reading.
        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, used.capturedChoice.kind);
        assertEquals("Chef", used.capturedChoice.discipline);
        assertEquals("Aria", used.capturedChoice.charName);
        assertEquals(400, used.capturedChoice.rating);

        // The inventory character is independent of the scope's character (DOMAIN_SPEC.md §11.1).
        assertEquals("Borin", used.capturedInventoryCharacter);

        CraftingSettings settings = used.capturedSettings;
        assertFalse(settings.useOwnMats);
        assertFalse(settings.allowBuying);
        assertEquals(25_000, settings.maxBuyCopper);
        assertTrue(settings.listingSell);
        assertTrue(settings.listingBuy);
    }

    @Test
    void omittedSettingsFallBackToTheJavaFxDiscoveryDefaultsNotTheProfitOnes() throws Exception {
        factory.next(service -> service.canned = discoveryData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.useOwnMats").value(true))
                // Discovery opens with buying enabled at 20g, unlike Profit's off/1g.
                .andExpect(jsonPath("$.settings.allowBuying").value(true))
                .andExpect(jsonPath("$.settings.maxBuyCopper").value(200000))
                .andExpect(jsonPath("$.settings.listingSell").value(false))
                .andExpect(jsonPath("$.settings.listingBuy").value(false))
                // Not a request field: Discovery has always fixed it to false.
                .andExpect(jsonPath("$.settings.dailyBuyInsteadOfCraft").value(false));

        CraftingSettings settings = factory.only().capturedSettings;
        assertTrue(settings.useOwnMats);
        assertTrue(settings.allowBuying);
        assertEquals(200_000, settings.maxBuyCopper);
        assertFalse(settings.listingSell);
        assertFalse(settings.listingBuy);
        assertFalse(settings.dailyBuyInsteadOfCraft);
    }

    @Test
    void omittedInventoryCharacterReachesTheServiceAsNullSoItKeepsItsUnfilteredFallback() throws Exception {
        factory.next(service -> service.canned = discoveryData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + ", \"inventoryCharacterName\": \"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryCharacterName").doesNotExist());

        assertNull(factory.only().capturedInventoryCharacter,
                "a blank inventory character must reach the service as absent, not as a blank name");
    }

    @Test
    void completeResultIsMappedIncludingAuthoritativeTotalsAndMissingMaterials() throws Exception {
        CraftResult result = new CraftResult(100, "Chef", 4,
                new HashMap<>(Map.of(300, 9, 200, 5)), new HashMap<>(Map.of(200, 2)),
                1_234, 56, 7_890, 700, 2_800, null);

        factory.next(service -> service.canned = discoveryData(
                List.of(RECIPE),
                Map.of(RECIPE.recipeId, result),
                Map.of(100, new ItemRepository.ItemInfo(100, "Widget", null, null),
                        200, new ItemRepository.ItemInfo(200, "Ingot", null, null)),
                Map.of(100, new PriceQuote(900, 1_000), 200, new PriceQuote(10, 12))));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].recipeId").value(7))
                .andExpect(jsonPath("$.rows[0].outputItemId").value(100))
                .andExpect(jsonPath("$.rows[0].outputName").value("Widget"))
                .andExpect(jsonPath("$.rows[0].outputCount").value(2))
                .andExpect(jsonPath("$.rows[0].disciplines").value("Chef"))
                .andExpect(jsonPath("$.rows[0].minRating").value(275))
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(true))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(4))
                .andExpect(jsonPath("$.rows[0].buyCostCopper").value(1234))
                .andExpect(jsonPath("$.rows[0].matsSellValueCopper").value(56))
                .andExpect(jsonPath("$.rows[0].revenueCopper").value(7890))
                .andExpect(jsonPath("$.rows[0].profitCopper").value(700))
                // The domain's own total, never re-derived from count x per-craft profit.
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
    void blockedAndResultlessRowsAreReportedRatherThanOmitted() throws Exception {
        Recipe blocked = new Recipe(8, 101, 1, 0, "Chef", List.of());
        Recipe withoutResult = new Recipe(9, 102, 1, 0, "Chef", List.of());
        CraftResult blockedResult = new CraftResult(101, "Chef", 0,
                Map.of(), Map.of(), 0, 0, 0, 0, 0, null, BlockedReason.PRICE_UNAVAILABLE);

        factory.next(service -> service.canned = discoveryData(
                List.of(blocked, withoutResult),
                Map.of(blocked.recipeId, blockedResult),
                Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.rows[0].recipeId").value(8))
                .andExpect(jsonPath("$.rows[0].resultAvailable").value(true))
                .andExpect(jsonPath("$.rows[0].blockedReason").value("PRICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.rows[0].craftableCount").value(0))
                // The JavaFX Discovery table drops a recipe whose calculation produced no result;
                // the API reports it instead, so a client sees the complete recipe set.
                .andExpect(jsonPath("$.rows[1].recipeId").value(9))
                .andExpect(jsonPath("$.rows[1].resultAvailable").value(false))
                .andExpect(jsonPath("$.rows[1].totalProfitCopper").doesNotExist())
                .andExpect(jsonPath("$.rows[1].blockedReason").doesNotExist());
    }

    @Test
    void nothingLeftToDiscoverIsAnEmptySuccessNotAMissingResource() throws Exception {
        // Exactly what CraftingDiscoveryService returns from its "no missing recipes" short-circuit.
        factory.next(service -> service.canned = discoveryData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": {"discipline": "Chef", "characterName": "NoSuchCharacter",
                                           "rating": 500}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.scope.characterName").value("NoSuchCharacter"));
    }

    // ---------- invalid input ----------

    @Test
    void absentBodyIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "scope is required: Discovery lists the recipes missing for one discipline+character combination"));

        assertEquals(0, factory.created.size(), "an invalid request must not create or call the service");
    }

    @Test
    void bodyWithoutAScopeIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\": {\"allowBuying\": true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void blankDisciplineIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": {"discipline": "  ", "characterName": "Aria", "rating": 400}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scope.discipline is required"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void missingCharacterNameIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"discipline\": \"Chef\", \"rating\": 400}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scope.characterName is required"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void missingRatingIsRejectedRatherThanSilentlyFilteringEverythingOut() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"Aria\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "scope.rating is required: it filters out recipes above the character's crafting rating"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void negativeRatingIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": {"discipline": "Chef", "characterName": "Aria", "rating": -1}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scope.rating must not be negative"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void negativeMaxBuyCopperIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + ", \"settings\": {\"maxBuyCopper\": -1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("settings.maxBuyCopper must not be negative"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void malformedJsonIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void unknownPathUnderTheDiscoveryRouteIsANotFound() throws Exception {
        mockMvc.perform(get("/api/crafting/discovery/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, factory.created.size());
    }

    // ---------- mapped failures ----------

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        factory.next(service -> service.failure =
                new SQLException("connection to jdbc:postgresql://host/db refused"));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"));
    }

    @Test
    void craftingGraphCacheFailureIsMappedToInternalServerError() throws Exception {
        factory.next(service -> service.failure =
                new RuntimeException("Failed to load crafting graph cache"));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("CALCULATION_FAILED"))
                .andExpect(jsonPath("$.message").value("The crafting calculation could not be completed"));
    }

    // ---------- request isolation ----------

    @Test
    void successiveRequestsEachGetTheirOwnServiceAndTheirOwnResult() throws Exception {
        factory.deriveResultFromRequest = true;

        mockMvc.perform(discoveryRequestForCharacter(11))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(11))
                .andExpect(jsonPath("$.rows[0].outputName").value("C11"));

        mockMvc.perform(discoveryRequestForCharacter(22))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(22))
                .andExpect(jsonPath("$.rows[0].outputName").value("C22"));

        assertEquals(2, factory.created.size());
        assertNotSame(factory.created.get(0), factory.created.get(1),
                "each request must run on its own service instance, so no reload state is shared");
    }

    @Test
    void anEmptyResultNeverSurfacesAnEarlierRequestsRows() throws Exception {
        // The service deliberately keeps its previous lookup state when a reload finds nothing to
        // discover, so a shared instance could answer this second request with the first's rows.
        factory.next(service -> service.canned = discoveryData(
                List.of(RECIPE), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FULL_SCOPE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1));

        factory.next(service -> service.canned = discoveryData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": {"discipline": "Huntsman", "characterName": "Borin", "rating": 0}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.scope.discipline").value("Huntsman"));

        assertEquals(2, factory.created.size());
        assertNotSame(factory.created.get(0), factory.created.get(1));
    }

    @Test
    void concurrentRequestsWithDistinctInputsDoNotMixResults() throws Exception {
        factory.deriveResultFromRequest = true;
        int requests = 8;

        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int character = 100 + i;
            calls.add(() -> mockMvc.perform(discoveryRequestForCharacter(character))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].recipeId").value(character))
                    .andExpect(jsonPath("$.rows[0].outputName").value("C" + character))
                    .andExpect(jsonPath("$.scope.characterName").value("C" + character))
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

    private static MockHttpServletRequestBuilder discoveryRequestForCharacter(int character) {
        return post("/api/crafting/discovery")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"C" + character
                        + "\", \"rating\": 400}}");
    }

    private static int distinctInstances(List<StubDiscoveryService> instances) {
        Map<StubDiscoveryService, Boolean> identity = new IdentityHashMap<>();
        for (StubDiscoveryService instance : instances) identity.put(instance, Boolean.TRUE);
        return identity.size();
    }

    // ---------- stubs ----------

    private static CraftingDiscoveryService.DiscoveryData discoveryData(
            List<Recipe> visible,
            Map<Integer, CraftResult> results,
            Map<Integer, ItemRepository.ItemInfo> items,
            Map<Integer, PriceQuote> tp) {
        return new CraftingDiscoveryService.DiscoveryData(visible, visible, results, items, tp);
    }

    /**
     * Stands in for the application service: records what the controller handed it and returns
     * canned data (or fails), so these tests never touch a database or a real calculation.
     */
    private static final class StubDiscoveryService extends CraftingDiscoveryService {
        DiscChoice capturedChoice;
        CraftingSettings capturedSettings;
        String capturedInventoryCharacter;
        DiscoveryData canned = new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        Exception failure;
        boolean deriveResultFromRequest;

        StubDiscoveryService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public DiscoveryData reload(DiscChoice choice, CraftingSettings settings,
                                    String selectedCharacterName) throws SQLException {
            this.capturedChoice = choice;
            this.capturedSettings = settings;
            this.capturedInventoryCharacter = selectedCharacterName;

            if (failure instanceof SQLException sqlException) throw sqlException;
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;

            if (!deriveResultFromRequest) return canned;

            // Result derived from this request's own input, so a response that carried another
            // request's data would be visible as a mismatched recipe id / output name.
            int recipeId = Integer.parseInt(choice.charName.substring(1));
            Recipe recipe = new Recipe(recipeId, recipeId, 1, 0, choice.discipline, List.of());
            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DiscoveryData(List.of(recipe), List.of(recipe), Map.of(),
                    Map.of(recipeId, new ItemRepository.ItemInfo(recipeId, choice.charName, null, null)),
                    Map.of());
        }
    }

    /** The controller's per-request service seam, recording every instance it hands out. */
    private static final class RecordingServiceFactory implements Supplier<CraftingDiscoveryService> {
        final List<StubDiscoveryService> created = Collections.synchronizedList(new ArrayList<>());
        private Consumer<StubDiscoveryService> configure = service -> {};
        boolean deriveResultFromRequest;

        void next(Consumer<StubDiscoveryService> configure) {
            this.configure = configure;
        }

        StubDiscoveryService only() {
            assertEquals(1, created.size(), "expected exactly one service instance");
            return created.get(0);
        }

        @Override
        public CraftingDiscoveryService get() {
            StubDiscoveryService service = new StubDiscoveryService();
            service.deriveResultFromRequest = deriveResultFromRequest;
            configure.accept(service);
            created.add(service);
            return service;
        }
    }
}
