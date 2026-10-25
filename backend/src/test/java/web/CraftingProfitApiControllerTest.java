package web;

import application.CachedCraftingProfitService;
import application.CalculationFreshness;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    private static final application.access.AccountContext ACCOUNT_CONTEXT = new application.access.AccountContext(
            "11111111-2222-3333-4444-555555555555", java.util.Set.of(), java.util.Set.of(), "ctx-1",
            java.time.Instant.parse("2099-01-01T00:00:00Z"));

    private RecordingServiceFactory factory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        factory = new RecordingServiceFactory();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CraftingProfitApiController(factory, new web.access.AccountAccessHttp("")))
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                        .requestAttr(web.access.RequestAccountContext.ATTRIBUTE, ACCOUNT_CONTEXT))
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
                .andExpect(jsonPath("$.settings.allowDailyCrafts").value(false));

        StubProfitService used = factory.only();
        assertEquals(DiscChoice.Kind.ALL, used.capturedChoice.kind);
        CraftingSettings settings = used.capturedSettings;
        assertTrue(settings.useOwnMats);
        assertFalse(settings.allowBuying);
        assertEquals(10_000, settings.maxBuyCopper);
        assertFalse(settings.listingSell);
        assertFalse(settings.listingBuy);
        assertFalse(settings.allowDailyCrafts);
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
                                               "allowDailyCrafts": true}
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("CHARACTER_DISCIPLINE"))
                .andExpect(jsonPath("$.scope.discipline").value("Chef"))
                .andExpect(jsonPath("$.scope.characterName").value("Aria"))
                .andExpect(jsonPath("$.scope.rating").value(400))
                .andExpect(jsonPath("$.settings.maxBuyCopper").value(25000))
                .andExpect(jsonPath("$.settings.allowDailyCrafts").value(true));

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
        assertTrue(settings.allowDailyCrafts);
    }

    @Test
    void explicitDailyCraftFalseReachesRuntimeAndIsEchoedWithoutApplyingTheDefault() throws Exception {
        factory.next(service -> service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of()));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settings": {"allowDailyCrafts": false}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.allowDailyCrafts").value(false));

        assertFalse(factory.only().capturedSettings.allowDailyCrafts);
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
                1_234, 56, 7_890, 700, 2_800, 31_560, null, BlockedReason.NONE, 224,
                Map.of(200, new craft.MaterialPurchaseCost(12, 60),
                        300, new craft.MaterialPurchaseCost(null, null)),
                Map.of(200, new craft.MaterialPurchaseCost(12, 24)));

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
                .andExpect(jsonPath("$.rows[0].totalMatsSellValueCopper").value(224))
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
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].purchaseUnitPriceCopper").value(12))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].totalPurchaseCostCopper").value(60))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].price.sellUnitCopper").value(12))
                // Unknown item and unquoted item are reported as such, not dropped.
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].itemId").value(300))
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].itemName").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].quantity").value(9))
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].purchaseUnitPriceCopper").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].totalPurchaseCostCopper").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuy[1].price").doesNotExist())
                .andExpect(jsonPath("$.rows[0].missingToBuyOne.length()").value(1))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].itemId").value(200))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].quantity").value(2))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].purchaseUnitPriceCopper").value(12))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].totalPurchaseCostCopper").value(24));
    }

    @Test
    void missingMaterialPurchaseValuesComeFromTheConfiguredAcquisitionMode() throws Exception {
        CraftResult result = new CraftResult(100, "Artificer", 4,
                new HashMap<>(Map.of(200, 5)), new HashMap<>(Map.of(200, 2)),
                60, 0, 7_890, 700, 2_800, 31_560, null, BlockedReason.NONE, 0,
                Map.of(200, new craft.MaterialPurchaseCost(10, 50)),
                Map.of(200, new craft.MaterialPurchaseCost(10, 20)));
        factory.next(service -> service.canned = profitData(List.of(RECIPE),
                Map.of(RECIPE.recipeId, result), Map.of(200, new ItemRepository.ItemInfo(200, "Ingot", null, null)),
                Map.of(200, new PriceQuote(10, 12))));

        mockMvc.perform(post("/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingBuy\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].purchaseUnitPriceCopper").value(10))
                .andExpect(jsonPath("$.rows[0].missingToBuy[0].totalPurchaseCostCopper").value(50))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].purchaseUnitPriceCopper").value(10))
                .andExpect(jsonPath("$.rows[0].missingToBuyOne[0].totalPurchaseCostCopper").value(20));
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

    // ---------- cached-result contract (STORY-APP-014, TARGET_ARCHITECTURE.md §9.1) ----------

    @Test
    void expiredInputsStillAnswerTwoHundredWithTheResultAndAProvisionalEnvelope() throws Exception {
        factory.next(service -> {
            service.canned = profitData(List.of(RECIPE), Map.of(), Map.of(), Map.of());
            service.freshness = freshness("req-7", "gen-7", true, true,
                    List.of(stale("BANK"), stale("MATERIALS"), stale("ACCOUNT_RECIPES"),
                            fresh("CHARACTERS")),
                    new CalculationFreshness.PriceCoverage(2_984, 2_900, 60, 24,
                            Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-12T08:55:00Z"),
                            false, "quotes-7"),
                    List.of(new CalculationFreshness.OutstandingRefresh(
                                    CalculationFreshness.RefreshKind.ACCOUNT,
                                    Set.of("MATERIALS", "BANK", "ACCOUNT_RECIPES"), "task-a", "RUNNING",
                                    "ADMITTED"),
                            new CalculationFreshness.OutstandingRefresh(
                                    CalculationFreshness.RefreshKind.TRADING_POST,
                                    Set.of(), "task-t", "PENDING", "COALESCED_INTO_ACTIVE_RUN")),
                    List.of());
        });

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                // expiry alone must never reject a usable result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].recipeId").value(7))
                .andExpect(jsonPath("$.freshness.requestId").value("req-7"))
                .andExpect(jsonPath("$.freshness.calculationId").value("gen-7"))
                .andExpect(jsonPath("$.freshness.evaluatedAt").value("2026-10-12T09:00:00Z"))
                .andExpect(jsonPath("$.freshness.provisional").value(true))
                .andExpect(jsonPath("$.freshness.resultAvailable").value(true))
                .andExpect(jsonPath("$.freshness.accountSources.length()").value(4))
                .andExpect(jsonPath("$.freshness.accountSources[0].source").value("BANK"))
                .andExpect(jsonPath("$.freshness.accountSources[0].state").value("STALE"))
                .andExpect(jsonPath("$.freshness.accountSources[0].fetchedAt")
                        .value("2026-10-12T07:00:00Z"))
                .andExpect(jsonPath("$.freshness.accountSources[3].source").value("CHARACTERS"))
                .andExpect(jsonPath("$.freshness.accountSources[3].state").value("FRESH"))
                .andExpect(jsonPath("$.freshness.tradingPost.requiredItems").value(2984))
                .andExpect(jsonPath("$.freshness.tradingPost.staleItems").value(60))
                .andExpect(jsonPath("$.freshness.tradingPost.missingItems").value(24))
                .andExpect(jsonPath("$.freshness.tradingPost.covered").value(false))
                // account and price work stay separately identified, each with its own status path
                .andExpect(jsonPath("$.freshness.outstandingWork.length()").value(2))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].kind").value("ACCOUNT"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].sources.length()").value(3))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].sources[0]").value("ACCOUNT_RECIPES"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].sources[1]").value("BANK"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].sources[2]").value("MATERIALS"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskState").value("RUNNING"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskStatusUrl")
                        .value("/api/sync/tasks/task-a"))
                .andExpect(jsonPath("$.freshness.outstandingWork[0].reuse").value("ADMITTED"))
                .andExpect(jsonPath("$.freshness.outstandingWork[1].kind").value("TRADING_POST"))
                .andExpect(jsonPath("$.freshness.outstandingWork[1].sources.length()").value(0))
                .andExpect(jsonPath("$.freshness.outstandingWork[1].taskStatusUrl")
                        .value("/api/sync/tasks/task-t"))
                .andExpect(jsonPath("$.freshness.failures.length()").value(0));
    }

    @Test
    void confirmedCurrentInputsAnswerAFinalEnvelopeWithNoOutstandingWork() throws Exception {
        factory.next(service -> {
            service.canned = profitData(List.of(RECIPE), Map.of(), Map.of(), Map.of());
            service.freshness = freshness("req-1", "gen-1", false, true,
                    List.of(fresh("BANK"), fresh("MATERIALS"), fresh("ACCOUNT_RECIPES"),
                            fresh("CHARACTERS")),
                    new CalculationFreshness.PriceCoverage(120, 120, 0, 0,
                            Instant.parse("2026-10-12T08:55:00Z"), Instant.parse("2026-10-12T08:59:00Z"),
                            true, "quotes-1"),
                    List.of(), List.of());
        });

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.freshness.provisional").value(false))
                .andExpect(jsonPath("$.freshness.tradingPost.covered").value(true))
                .andExpect(jsonPath("$.freshness.outstandingWork.length()").value(0));
    }

    @Test
    void noUsableCachedInputsAnswerTwoHundredTwoWithFreshnessAndTaskReferencesAndNoRows() throws Exception {
        factory.next(service -> {
            service.resultAvailable = false;
            service.freshness = freshness("req-9", "gen-9", true, false,
                    List.of(missing("BANK"), fresh("MATERIALS"), fresh("ACCOUNT_RECIPES"),
                            fresh("CHARACTERS")),
                    CalculationFreshness.PriceCoverage.none(),
                    List.of(new CalculationFreshness.OutstandingRefresh(
                            CalculationFreshness.RefreshKind.ACCOUNT, Set.of("BANK"),
                            "task-b", "RUNNING", "ADMITTED")),
                    List.of(new CalculationFreshness.RefreshFailure(
                            CalculationFreshness.RefreshKind.ACCOUNT, "task-old", "TASK_UNKNOWN",
                            "The refresh task is no longer known to this backend", true)));
        });

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                // no fabricated result: absent rows, not an empty list that would read as
                // "nothing is craftable"
                .andExpect(jsonPath("$.rows").doesNotExist())
                .andExpect(jsonPath("$.rowCount").doesNotExist())
                // the effective inputs are still echoed, so the client knows what was asked for
                .andExpect(jsonPath("$.scope.kind").value("ALL"))
                .andExpect(jsonPath("$.settings.useOwnMats").value(true))
                .andExpect(jsonPath("$.freshness.resultAvailable").value(false))
                .andExpect(jsonPath("$.freshness.provisional").value(true))
                .andExpect(jsonPath("$.freshness.accountSources[0].source").value("BANK"))
                .andExpect(jsonPath("$.freshness.accountSources[0].state").value("MISSING"))
                .andExpect(jsonPath("$.freshness.accountSources[0].fetchedAt").doesNotExist())
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskStatusUrl")
                        .value("/api/sync/tasks/task-b"))
                .andExpect(jsonPath("$.freshness.failures[0].code").value("TASK_UNKNOWN"))
                .andExpect(jsonPath("$.freshness.failures[0].retryable").value(true))
                .andExpect(jsonPath("$.freshness.failures[0].taskStatusUrl")
                        .value("/api/sync/tasks/task-old"));
    }

    @Test
    void workWithoutATaskReportsNoStatusUrlRatherThanAFabricatedOne() throws Exception {
        factory.next(service -> {
            service.canned = profitData(List.of(), Map.of(), Map.of(), Map.of());
            service.freshness = freshness("req-2", "gen-2", true, true,
                    List.of(stale("BANK")), CalculationFreshness.PriceCoverage.none(),
                    List.of(new CalculationFreshness.OutstandingRefresh(
                            CalculationFreshness.RefreshKind.ACCOUNT, Set.of("BANK"),
                            null, "NONE", "WAITING_FOR_UNRELATED_TASK")),
                    List.of());
        });

        mockMvc.perform(post("/api/crafting/profit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskId").doesNotExist())
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskStatusUrl").doesNotExist())
                .andExpect(jsonPath("$.freshness.outstandingWork[0].taskState").value("NONE"));
    }

    @Test
    void twoInterleavedRequestContextsKeepTheirOwnEnvelopesAndTheirOwnResults() throws Exception {
        factory.deriveResultFromRequest = true;

        String first = mockMvc.perform(profitRequestForDiscipline(41))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(41))
                .andExpect(jsonPath("$.freshness.requestId").value("request-D41"))
                .andExpect(jsonPath("$.freshness.calculationId").value("generation-D41"))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(profitRequestForDiscipline(42))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recipeId").value(42))
                .andExpect(jsonPath("$.freshness.requestId").value("request-D42"))
                .andExpect(jsonPath("$.freshness.calculationId").value("generation-D42"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(first.contains("D42"), "the first response must carry nothing of the second");
        assertFalse(second.contains("D41"), "the second response must carry nothing of the first");
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
                "each request must run as its own calculation, so no result state is shared");
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

        assertEquals(requests, factory.created.size(), "one calculation per request");
        assertEquals(requests, distinctInstances(factory.created),
                "concurrent requests must not share calculation state");
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
     * One recorded call: what the controller handed the use case, and what that call is set up to
     * answer with. Keeping the per-call state here is what lets the isolation tests below assert
     * that two requests never share it.
     */
    private static final class StubProfitService {
        DiscChoice capturedChoice;
        CraftingSettings capturedSettings;
        String capturedBasedOnCalculationId;
        CraftingProfitService.ProfitData canned =
                new CraftingProfitService.ProfitData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        /** Null means "no usable cached inputs", i.e. the pending outcome. */
        boolean resultAvailable = true;
        CalculationFreshness freshness;
        Exception failure;
        boolean deriveResultFromRequest;
    }

    /**
     * Stands in for the cached-result use case: records what the controller handed it and returns a
     * canned outcome (or fails), so these tests never touch a database, a task or a real
     * calculation. Freshness decisions belong to the application layer and are stubbed here, which
     * is exactly the point - the controller must report the envelope it is given, not derive one.
     */
    private static final class RecordingServiceFactory extends CachedCraftingProfitService {
        final List<StubProfitService> created = Collections.synchronizedList(new ArrayList<>());
        private java.util.function.Consumer<StubProfitService> configure = service -> {};
        boolean deriveResultFromRequest;

        RecordingServiceFactory() {
            super(null, null, null, null, null, null, null, null, null);
        }

        // The routes call the scoped entry points (STORY-API-011); the recorded behaviour is the
        // same ordinary calculation, so the scoped forms delegate to the established fakes.
        @Override
        public ProfitOutcome profit(model.AccountScope scope, DiscChoice choice, CraftingSettings settings)
                throws Exception {
            assertEquals(ACCOUNT_CONTEXT.accountId(), scope.id().toString());
            return profit(choice, settings);
        }

        @Override
        public ResolutionOutcome resolution(model.AccountScope scope, int recipeId, DiscChoice choice,
                                            CraftingSettings settings, String basedOnCalculationId)
                throws Exception {
            assertEquals(ACCOUNT_CONTEXT.accountId(), scope.id().toString());
            return resolution(recipeId, choice, settings, basedOnCalculationId);
        }

        void next(java.util.function.Consumer<StubProfitService> configure) {
            this.configure = configure;
        }

        StubProfitService only() {
            assertEquals(1, created.size(), "expected exactly one recorded calculation");
            return created.get(0);
        }

        /** The most recent call, for a test that performs more than one request. */
        StubProfitService last() {
            assertFalse(created.isEmpty(), "expected at least one recorded calculation");
            return created.get(created.size() - 1);
        }

        @Override
        public ProfitOutcome profit(DiscChoice choice, CraftingSettings settings) throws Exception {
            StubProfitService call = newCall();
            call.capturedChoice = choice;
            call.capturedSettings = settings;

            if (call.failure instanceof SQLException sqlException) throw sqlException;
            if (call.failure instanceof RuntimeException runtimeException) throw runtimeException;

            if (!call.resultAvailable) {
                return new ProfitOutcome(null, freshnessOf(call, false));
            }

            if (!call.deriveResultFromRequest) {
                return new ProfitOutcome(call.canned, freshnessOf(call, true));
            }

            // Result derived from this request's own input, so a response that carried another
            // request's data would be visible as a mismatched recipe id / output name.
            int recipeId = Integer.parseInt(choice.discipline.substring(1));
            Recipe recipe = new Recipe(recipeId, recipeId, 1, 0, choice.discipline, List.of());
            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CraftingProfitService.ProfitData derived = new CraftingProfitService.ProfitData(
                    List.of(recipe), List.of(recipe), Map.of(),
                    Map.of(recipeId, new ItemRepository.ItemInfo(recipeId, choice.discipline, null, null)),
                    Map.of());
            return new ProfitOutcome(derived, freshness(
                    "request-" + choice.discipline, "generation-" + choice.discipline, true, true,
                    List.of(), CalculationFreshness.PriceCoverage.none(), List.of(), List.of()));
        }

        private StubProfitService newCall() {
            StubProfitService call = new StubProfitService();
            call.deriveResultFromRequest = deriveResultFromRequest;
            configure.accept(call);
            created.add(call);
            return call;
        }

        private static CalculationFreshness freshnessOf(StubProfitService call, boolean resultAvailable) {
            if (call.freshness != null) return call.freshness;
            return freshness("request-1", "generation-1", true, resultAvailable, List.of(),
                    CalculationFreshness.PriceCoverage.none(), List.of(), List.of());
        }
    }

    private static CalculationFreshness.SourceFreshness fresh(String source) {
        return new CalculationFreshness.SourceFreshness(source, Instant.parse("2026-10-12T08:57:00Z"),
                CalculationFreshness.SourceState.FRESH);
    }

    private static CalculationFreshness.SourceFreshness stale(String source) {
        return new CalculationFreshness.SourceFreshness(source, Instant.parse("2026-10-12T07:00:00Z"),
                CalculationFreshness.SourceState.STALE);
    }

    private static CalculationFreshness.SourceFreshness missing(String source) {
        return new CalculationFreshness.SourceFreshness(source, null,
                CalculationFreshness.SourceState.MISSING);
    }

    static CalculationFreshness freshness(String requestId,
                                          String calculationId,
                                          boolean provisional,
                                          boolean resultAvailable,
                                          List<CalculationFreshness.SourceFreshness> sources,
                                          CalculationFreshness.PriceCoverage coverage,
                                          List<CalculationFreshness.OutstandingRefresh> outstanding,
                                          List<CalculationFreshness.RefreshFailure> failures) {
        return new CalculationFreshness(requestId, calculationId, Instant.parse("2026-10-12T09:00:00Z"),
                provisional, resultAvailable, sources, coverage, outstanding, failures);
    }
}
