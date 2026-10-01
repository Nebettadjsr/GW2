package web;

import application.CraftingDiscoveryService;
import application.CraftingResolutionDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.AcquisitionMethod;
import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftTraceNode;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import craft.ResolutionState;
import craft.SingleCraftExplanation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;
import repo.ItemRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the Crafting Discovery resolution-detail route (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.1/§13.3/§13.4, TEST_STRATEGY.md §11).
 *
 * <p>The envelope, node shape and shared validation rules are the same as Profit's and are covered in
 * depth by {@link CraftingProfitResolutionApiControllerTest}; what is asserted here is what is
 * genuinely Discovery's own - its required individual scope (also the inventory scope), its own
 * settings defaults and fixed daily setting, and its empty-calculation behavior -
 * plus that the shared envelope and outcomes really are produced on this route too.
 *
 * <p>The application service is replaced by a stub, so no database, calculation or resolver runs
 * here and every asserted value is one the test supplied.
 */
class CraftingDiscoveryResolutionApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Recipe REQUESTED = new Recipe(
            5, 100, 1, 300, "Chef", List.of(new Ingredient(200, 2)));

    private static final Map<Integer, ItemRepository.ItemInfo> ITEMS = Map.of(
            100, new ItemRepository.ItemInfo(100, "Bowl of Soup", null, null),
            200, new ItemRepository.ItemInfo(200, "Carrot", null, null));

    private static final Map<Integer, PriceQuote> QUOTES = Map.of(
            100, new PriceQuote(400, 500),
            200, new PriceQuote(8, 9));

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

    // ---------- Discovery's own inputs ----------

    @Test
    void theSelectedCharacterAndSettingsReachTheServiceAndAreEchoedBack()
            throws Exception {
        factory.next(service -> service.canned = available(craftedRoot()));

        Instant before = Instant.now();
        String body = mockMvc.perform(resolution("""
                        {"recipeId": 5,
                         "calculation": {
                           "scope": {"discipline": "Chef", "characterName": "Aria", "rating": 400},
                           "settings": {"useOwnMats": false, "allowBuying": false,
                                        "listingSell": true, "listingBuy": true}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId").value(5))
                .andExpect(jsonPath("$.calculation.scope.discipline").value("Chef"))
                .andExpect(jsonPath("$.calculation.scope.characterName").value("Aria"))
                .andExpect(jsonPath("$.calculation.scope.rating").value(400))
                .andExpect(jsonPath("$.calculation.settings.useOwnMats").value(false))
                .andExpect(jsonPath("$.calculation.settings.allowBuying").value(false))
                .andExpect(jsonPath("$.calculation.settings.listingSell").value(true))
                .andExpect(jsonPath("$.calculation.settings.listingBuy").value(true))
                // Fixed by the Discovery flow, reported explicitly, never a selectable input.
                .andExpect(jsonPath("$.calculation.settings.allowDailyCrafts").value(true))
                .andExpect(jsonPath("$.consistency").value("FRESH_CALCULATION"))
                .andExpect(jsonPath("$.treeBasis").value("SINGLE_OUTPUT_REQUIREMENT"))
                .andExpect(jsonPath("$.treeStatus").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        Instant calculatedAt = Instant.parse(JSON.readTree(body).path("calculatedAt").asText());
        assertFalse(calculatedAt.isBefore(before.minusSeconds(1)));
        assertFalse(calculatedAt.isAfter(Instant.now().plusSeconds(1)));

        StubDiscoveryService used = factory.only();
        assertEquals(1, used.detailCalls, "the controller must delegate exactly once");
        assertEquals(0, used.reloadCalls, "detail must not run the table use case");
        assertEquals(5, used.capturedRecipeId);
        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, used.capturedChoice.kind);
        assertEquals("Chef", used.capturedChoice.discipline);
        assertEquals("Aria", used.capturedChoice.charName);
        assertEquals(400, used.capturedChoice.rating);
        assertEquals("Aria", used.capturedInventoryCharacter);
        assertTrue(used.capturedSettings.allowDailyCrafts);
        assertEquals(0, used.capturedSettings.maxBuyCopper);
    }


    @Test
    void omittedSettingsFallBackToDiscoverysOwnDefaultsNotProfits() throws Exception {
        factory.next(service -> service.canned = available(craftedRoot()));

        mockMvc.perform(resolution("""
                        {"recipeId": 5,
                         "calculation": {"scope": {"discipline": "Chef", "characterName": "Aria",
                                                   "rating": 400}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.settings.useOwnMats").value(true))
                // Discovery defaults to buying on; it has no cumulative budget.
                .andExpect(jsonPath("$.calculation.settings.allowBuying").value(true))
                .andExpect(jsonPath("$.calculation.settings.listingSell").value(false))
                .andExpect(jsonPath("$.calculation.settings.listingBuy").value(false))
                .andExpect(jsonPath("$.calculation.settings.allowDailyCrafts").value(true));

        CraftingSettings settings = factory.only().capturedSettings;
        assertTrue(settings.allowBuying);
        assertEquals(0, settings.maxBuyCopper);
        assertTrue(settings.allowDailyCrafts);
    }

    @Test
    void theRequiredScopeIsValidatedByTheTableRoutesOwnRulesBeforeAnyCalculation() throws Exception {
        mockMvc.perform(resolution("{\"recipeId\": 5, \"calculation\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "scope is required: Discovery lists the recipes missing for one discipline+character combination"));

        mockMvc.perform(resolution("""
                        {"recipeId": 5,
                         "calculation": {"scope": {"discipline": "Chef", "characterName": "Aria"}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "scope.rating is required: it filters out recipes above the character's crafting rating"));

        mockMvc.perform(resolution("""
                        {"recipeId": 5,
                         "calculation": {"scope": {"discipline": "Chef", "rating": 400}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("scope.characterName is required"));

        assertEquals(0, factory.created.size(), "an invalid request must not create or call the service");
    }

    @Test
    void theRecipeIdRulesAndAnAbsentBodyAreRejectedBeforeAnyCalculation() throws Exception {
        mockMvc.perform(post("/api/crafting/discovery/resolution"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("a request body is required: it must carry recipeId and calculation"));

        mockMvc.perform(resolution("""
                        {"calculation": {"scope": {"discipline": "Chef", "characterName": "Aria",
                                                   "rating": 400}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "recipeId is required: resolution detail explains one explicitly selected recipe"));

        mockMvc.perform(resolution("""
                        {"recipeId": -5,
                         "calculation": {"scope": {"discipline": "Chef", "characterName": "Aria",
                                                   "rating": 400}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("recipeId must be a positive integer"));

        mockMvc.perform(resolution("{\"recipeId\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "calculation is required: it carries the scope and settings the recipe is resolved in"));

        mockMvc.perform(resolution("{\"recipeId\": 5, \"calculation\": {\"scope\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertEquals(0, factory.created.size());
    }

    // ---------- the shared envelope, row and tree on this route ----------

    @Test
    void theRowAndTreeAreMappedFromThisOperationsOwnFacts() throws Exception {
        factory.next(service -> service.canned = available(craftedRoot()));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.recipeId").value(5))
                .andExpect(jsonPath("$.row.outputName").value("Bowl of Soup"))
                .andExpect(jsonPath("$.row.minRating").value(300))
                .andExpect(jsonPath("$.row.resultAvailable").value(true))
                .andExpect(jsonPath("$.row.craftableCount").value(1))
                .andExpect(jsonPath("$.row.outputPrice.sellUnitCopper").value(500))
                .andExpect(jsonPath("$.tree.itemId").value(100))
                .andExpect(jsonPath("$.tree.itemName").value("Bowl of Soup"))
                .andExpect(jsonPath("$.tree.requestedQuantity").value(1))
                .andExpect(jsonPath("$.tree.recipeId").value(5))
                .andExpect(jsonPath("$.tree.craftCount").value(1))
                .andExpect(jsonPath("$.tree.methods[0]").value("CRAFT"))
                .andExpect(jsonPath("$.tree.effectiveCostCopper").value(18))
                .andExpect(jsonPath("$.tree.children[0].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[0].itemName").value("Carrot"))
                .andExpect(jsonPath("$.tree.children[0].boughtQuantity").value(2))
                .andExpect(jsonPath("$.tree.children[0].children.length()").value(0));
    }

    /**
     * STORY-DOM-023: the fresh-detail row carries the domain's own figures - DOMAIN_SPEC.md
     * section 25's fee-corrected profits beside the untouched gross revenue and total sell value -
     * and derives none of them. The supplied numbers are deliberately unrelated to one another, so a
     * boundary that multiplied a count, netted a cost off or deducted a fee of its own would
     * disagree with every one of them.
     */
    @Test
    void theRowsProfitsAndGrossValuesAreTheDomainsOwnOnThisRouteToo() throws Exception {
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                5, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED,
                new CraftResult(100, "Chef", 4, Map.of(), Map.of(), 1_234, 56, 7_890, 700, 2_800,
                        9_999, null, BlockedReason.NONE),
                new SingleCraftExplanation(5, 100, 1, true, craftedRoot()), ITEMS, QUOTES));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.revenueCopper").value(7890))
                .andExpect(jsonPath("$.row.totalSellValueCopper").value(9999))
                .andExpect(jsonPath("$.row.profitCopper").value(700))
                .andExpect(jsonPath("$.row.totalProfitCopper").value(2800))
                .andExpect(jsonPath("$.row.matsSellValueCopper").value(56))
                .andExpect(jsonPath("$.row.buyCostCopper").value(1234));

        // A result the calculation could not produce keeps every money field absent rather than
        // reporting a fee-adjusted zero.
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                5, CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, REQUESTED, null,
                SingleCraftExplanation.unavailable(5, 100, 1), ITEMS, QUOTES));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.revenueCopper").doesNotExist())
                .andExpect(jsonPath("$.row.profitCopper").doesNotExist())
                .andExpect(jsonPath("$.row.totalProfitCopper").doesNotExist())
                .andExpect(jsonPath("$.row.totalSellValueCopper").doesNotExist());
    }

    @Test
    void aBlockedTreeIsACompleted200AndAnUnavailableResultIsOneWithNoTree() throws Exception {
        CraftTraceNode blockedRoot = new CraftTraceNode(100, 1, 0, 0, 0, 1, 5, 0, 0, null,
                List.of(), List.of(ResolutionState.BLOCKED), List.of(BlockedReason.BUYING_DISABLED),
                null, null, null, List.of());
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                5, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED,
                new CraftResult(100, "Chef", 0, Map.of(), Map.of(), 0, 0, 0, 0, 0, null,
                        BlockedReason.BUYING_DISABLED),
                new SingleCraftExplanation(5, 100, 1, true, blockedRoot), ITEMS, QUOTES));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.tree.blockedReasons[0]").value("BUYING_DISABLED"))
                .andExpect(jsonPath("$.tree.effectiveCostCopper").doesNotExist())
                .andExpect(jsonPath("$.row.blockedReason").value("BUYING_DISABLED"));

        factory.next(service -> service.canned = new CraftingResolutionDetail(
                5, CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, REQUESTED, null,
                SingleCraftExplanation.unavailable(5, 100, 1), ITEMS, QUOTES));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeStatus").value("RESULT_UNAVAILABLE"))
                .andExpect(jsonPath("$.tree").doesNotExist())
                // The recipe itself is still reported, with the row's existing unavailable semantics.
                .andExpect(jsonPath("$.row.recipeId").value(5))
                .andExpect(jsonPath("$.row.resultAvailable").value(false))
                .andExpect(jsonPath("$.row.craftableCount").doesNotExist());
    }

    @Test
    void aRecipeOutsideThisCalculationIsA404WithTheDocumentedErrorBody() throws Exception {
        factory.next(service -> service.canned = notInCalculationDetail(4_242));

        mockMvc.perform(resolution(validRequest(4_242)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECIPE_NOT_IN_CALCULATION"))
                .andExpect(jsonPath("$.message")
                        .value("Recipe 4242 is not part of this calculation's visible recipes"));

        assertEquals(4_242, factory.only().capturedRecipeId);
    }

    @Test
    void databaseAndCalculationFailuresKeepTheExistingSafeMapping() throws Exception {
        factory.next(service -> service.failure =
                new SQLException("connection to jdbc:postgresql://host/db refused as user gw2"));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"));

        factory.next(service -> service.failure = new RuntimeException("Failed to load crafting graph cache"));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("CALCULATION_FAILED"))
                .andExpect(jsonPath("$.message").value("The crafting calculation could not be completed"));
    }

    // ---------- request isolation ----------

    @Test
    void anEmptyCalculationAfterAPopulatedOneCannotSurfaceTheEarlierTree() throws Exception {
        factory.next(service -> service.canned = available(craftedRoot()));

        mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.itemId").value(100));

        // Nothing left to discover for this character: the operation has no candidate at all.
        factory.next(service -> service.canned = notInCalculationDetail(5));

        String body = mockMvc.perform(resolution(validRequest(5)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECIPE_NOT_IN_CALCULATION"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"tree\""), "a 404 must not carry any tree: " + body);
        assertFalse(body.contains("\"row\""), "a 404 must not carry any row: " + body);
        assertEquals(2, factory.created.size());
        assertNotSame(factory.created.get(0), factory.created.get(1));
    }

    @Test
    void concurrentRequestsWithDistinctRecipesDoNotMixDetails() throws Exception {
        factory.deriveResultFromRequest = true;
        int requests = 8;

        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int recipeId = 100 + i;
            calls.add(() -> mockMvc.perform(resolution(validRequest(recipeId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipeId").value(recipeId))
                    .andExpect(jsonPath("$.tree.recipeId").value(recipeId))
                    .andExpect(jsonPath("$.row.recipeId").value(recipeId))
                    .andReturn().getResponse().getContentAsString());
        }

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<String>> futures = pool.invokeAll(calls);
            for (Future<String> future : futures) {
                assertNotNull(future.get(), "every concurrent request must answer with its own detail");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(requests, factory.created.size(), "one service instance per request");
        Map<StubDiscoveryService, Boolean> identity = new java.util.IdentityHashMap<>();
        for (StubDiscoveryService instance : factory.created) identity.put(instance, Boolean.TRUE);
        assertEquals(requests, identity.size(), "concurrent requests must not share a service instance");
    }

    // ---------- fixtures ----------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            resolution(String body) {
        return post("/api/crafting/discovery/resolution")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String validRequest(int recipeId) {
        return """
                {"recipeId": %d,
                 "calculation": {"scope": {"discipline": "Chef", "characterName": "Aria", "rating": 400},
                                 "settings": {"useOwnMats": true}}}""".formatted(recipeId);
    }

    /** One execution of the requested recipe, its single ingredient bought. */
    private static CraftTraceNode craftedRoot() {
        CraftTraceNode child = new CraftTraceNode(200, 2, 0, 0, 2, 0, null, 0, 0, null,
                List.of(AcquisitionMethod.BUY), List.of(), List.of(), 18, 0, 18, List.of());
        return new CraftTraceNode(100, 1, 0, 1, 0, 0, 5, 1, 1, "Aria",
                List.of(AcquisitionMethod.CRAFT), List.of(), List.of(), 18, 0, 18, List.of(child));
    }

    private static CraftingResolutionDetail available(CraftTraceNode root) {
        return new CraftingResolutionDetail(
                5, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED,
                new CraftResult(100, "Chef", 1, Map.of(), Map.of(), 18, 0, 400, 382, 382, null),
                new SingleCraftExplanation(5, 100, 1, true, root), ITEMS, QUOTES);
    }

    private static CraftingResolutionDetail notInCalculationDetail(int recipeId) {
        return new CraftingResolutionDetail(
                recipeId, CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION,
                null, null, null, Map.of(), Map.of());
    }

    // ---------- stubs ----------

    /**
     * Stands in for the application service: records what the controller handed it and returns a
     * canned detail (or fails), so these tests never touch a database or a real calculation.
     */
    private static final class StubDiscoveryService extends CraftingDiscoveryService {
        int capturedRecipeId;
        DiscChoice capturedChoice;
        CraftingSettings capturedSettings;
        String capturedInventoryCharacter;
        int detailCalls;
        int reloadCalls;
        CraftingResolutionDetail canned;
        Exception failure;
        boolean deriveResultFromRequest;

        StubDiscoveryService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public DiscoveryData reload(DiscChoice choice, CraftingSettings settings) {
            reloadCalls++;
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }

        @Override
        public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                     CraftingSettings settings) throws SQLException {
            detailCalls++;
            this.capturedRecipeId = recipeId;
            this.capturedChoice = choice;
            this.capturedSettings = settings;
            this.capturedInventoryCharacter = choice.charName;

            if (failure instanceof SQLException sqlException) throw sqlException;
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;

            if (!deriveResultFromRequest) return canned;

            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Recipe recipe = new Recipe(recipeId, recipeId, 1, 0, "Chef", List.of());
            CraftTraceNode root = new CraftTraceNode(recipeId, 1, 0, 1, 0, 0, recipeId, 1, 1, null,
                    List.of(AcquisitionMethod.CRAFT), List.of(), List.of(), 1, 0, 1, List.of());
            return new CraftingResolutionDetail(
                    recipeId, CraftingResolutionDetail.Status.AVAILABLE, recipe,
                    new CraftResult(recipeId, "Chef", 1, Map.of(), Map.of(), 1, 0, 2, 1, 1, null),
                    new SingleCraftExplanation(recipeId, recipeId, 1, true, root),
                    Map.of(recipeId, new ItemRepository.ItemInfo(recipeId, "Item " + recipeId, null, null)),
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
