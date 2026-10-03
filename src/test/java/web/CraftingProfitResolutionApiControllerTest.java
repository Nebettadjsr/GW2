package web;

import application.CraftingProfitService;
import application.CraftingResolutionDetail;
import com.fasterxml.jackson.databind.JsonNode;
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
import web.dto.CraftingRowDto;

import java.lang.reflect.RecordComponent;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the Crafting Profit resolution-detail route (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.1/§13.3/§13.4, TEST_STRATEGY.md §11): required inputs, the effective
 * inputs echoed back, the complete envelope and recursive node shape, the §13.4 outcomes, and
 * request isolation at the boundary.
 *
 * <p>The application service is replaced by a stub that returns a supplied
 * {@link CraftingResolutionDetail}, so these exercise transport behavior only. Every asserted value
 * is one the test handed the controller; no resolution, cost or quantity is recomputed here - that
 * is what {@code craft.*} and {@code application.*} own, and recomputing it would prove the mapper
 * against itself. The tree cases therefore assert that supplied domain facts survive the boundary
 * unaltered, including the ones a naive mapper would be tempted to repair.
 */
class CraftingProfitResolutionApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The requested recipe: one batch of 2 Widgets from 3 Ingots. */
    private static final Recipe REQUESTED = new Recipe(
            7, 100, 2, 275, "Artificer", List.of(new Ingredient(200, 3)));

    private static final Map<Integer, ItemRepository.ItemInfo> ITEMS = Map.of(
            100, new ItemRepository.ItemInfo(100, "Widget", null, null),
            200, new ItemRepository.ItemInfo(200, "Ingot", null, null));

    /** Retained icon sources and the application URLs they must derive (STORY-API-009). */
    private static final String OUTPUT_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String OUTPUT_ICON_URL =
            "/api/items/100/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png";
    private static final String INGOT_SOURCE = "https://render.guildwars2.com/file/FEDCBA9876543210/4321.jpg";
    private static final String INGOT_ICON_URL =
            "/api/items/200/icon/9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d.jpg";

    private static final Map<Integer, PriceQuote> QUOTES = Map.of(
            100, new PriceQuote(900, 1_000),
            200, new PriceQuote(10, 12));

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

    // ---------- required inputs, defaults and the echoed calculation ----------

    @Test
    void explicitCalculationInputsReachTheServiceUnchangedAndAreEchoedBack() throws Exception {
        factory.next(service -> service.canned = available(rootCraftedByRequestedRecipe()));

        mockMvc.perform(resolution("""
                        {"recipeId": 7,
                         "calculation": {
                           "scope": {"kind": "CHARACTER_DISCIPLINE", "discipline": "Chef",
                                     "characterName": "Aria", "rating": 400},
                           "settings": {"useOwnMats": false, "allowBuying": true, "maxBuyCopper": 25000,
                                        "listingSell": true, "listingBuy": true,
                                         "allowDailyCrafts": false}}} """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId").value(7))
                .andExpect(jsonPath("$.calculation.scope.kind").value("CHARACTER_DISCIPLINE"))
                .andExpect(jsonPath("$.calculation.scope.discipline").value("Chef"))
                .andExpect(jsonPath("$.calculation.scope.characterName").value("Aria"))
                .andExpect(jsonPath("$.calculation.scope.rating").value(400))
                .andExpect(jsonPath("$.calculation.settings.useOwnMats").value(false))
                .andExpect(jsonPath("$.calculation.settings.allowBuying").value(true))
                .andExpect(jsonPath("$.calculation.settings.maxBuyCopper").value(25000))
                .andExpect(jsonPath("$.calculation.settings.listingSell").value(true))
                .andExpect(jsonPath("$.calculation.settings.listingBuy").value(true))
                .andExpect(jsonPath("$.calculation.settings.allowDailyCrafts").value(false));

        StubProfitService used = factory.only();
        assertEquals(1, used.detailCalls, "the controller must delegate exactly once");
        assertEquals(0, used.reloadCalls, "detail must not run the table use case");
        assertEquals(7, used.capturedRecipeId);
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
        assertFalse(settings.allowDailyCrafts);
    }

    @Test
    void omittedCalculationMembersFallBackToTheTableRoutesEstablishedDefaults() throws Exception {
        factory.next(service -> service.canned = available(rootCraftedByRequestedRecipe()));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.scope.kind").value("ALL"))
                .andExpect(jsonPath("$.calculation.settings.useOwnMats").value(true))
                .andExpect(jsonPath("$.calculation.settings.allowBuying").value(false))
                .andExpect(jsonPath("$.calculation.settings.maxBuyCopper").value(10000))
                .andExpect(jsonPath("$.calculation.settings.listingSell").value(false))
                .andExpect(jsonPath("$.calculation.settings.listingBuy").value(false))
                .andExpect(jsonPath("$.calculation.settings.allowDailyCrafts").value(false));

        StubProfitService used = factory.only();
        assertEquals(DiscChoice.Kind.ALL, used.capturedChoice.kind);
        assertTrue(used.capturedSettings.useOwnMats);
        assertEquals(10_000, used.capturedSettings.maxBuyCopper);
        assertFalse(used.capturedSettings.allowDailyCrafts);
    }

    @Test
    void theEnvelopeCarriesTheFixedLiteralsAndACompletionTimestamp() throws Exception {
        factory.next(service -> service.canned = available(rootCraftedByRequestedRecipe()));

        Instant before = Instant.now();
        String body = mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistency").value("FRESH_CALCULATION"))
                .andExpect(jsonPath("$.treeBasis").value("SELECTED_RESULT_OUTPUT_QUANTITY"))
                .andExpect(jsonPath("$.treeStatus").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        Instant calculatedAt = Instant.parse(JSON.readTree(body).path("calculatedAt").asText());
        assertFalse(calculatedAt.isBefore(before.minusSeconds(1)), "calculatedAt must be this completion");
        assertFalse(calculatedAt.isAfter(Instant.now().plusSeconds(1)), "calculatedAt must be this completion");
    }

    @Test
    void theRowIsTheSharedTableRowProjectionForThisOperation() throws Exception {
        CraftResult row = new CraftResult(100, "Artificer", 4,
                new HashMap<>(Map.of(300, 9, 200, 5)), new HashMap<>(Map.of(200, 2)),
                1_234, 56, 7_890, 700, 2_800, null);
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                7, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED, row,
                new SingleCraftExplanation(7, 100, 2, true, rootCraftedByRequestedRecipe()),
                ITEMS, QUOTES));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.recipeId").value(7))
                .andExpect(jsonPath("$.row.outputItemId").value(100))
                .andExpect(jsonPath("$.row.outputName").value("Widget"))
                .andExpect(jsonPath("$.row.outputCount").value(2))
                .andExpect(jsonPath("$.row.disciplines").value("Artificer"))
                .andExpect(jsonPath("$.row.minRating").value(275))
                .andExpect(jsonPath("$.row.resultAvailable").value(true))
                .andExpect(jsonPath("$.row.craftableCount").value(4))
                .andExpect(jsonPath("$.row.buyCostCopper").value(1234))
                .andExpect(jsonPath("$.row.matsSellValueCopper").value(56))
                .andExpect(jsonPath("$.row.revenueCopper").value(7890))
                .andExpect(jsonPath("$.row.profitCopper").value(700))
                // The domain's own total, never recomputed from count x per-craft profit.
                .andExpect(jsonPath("$.row.totalProfitCopper").value(2800))
                .andExpect(jsonPath("$.row.blockedReason").value("NONE"))
                .andExpect(jsonPath("$.row.outputPrice.buyUnitCopper").value(900))
                .andExpect(jsonPath("$.row.outputPrice.sellUnitCopper").value(1000))
                .andExpect(jsonPath("$.row.missingToBuy.length()").value(2))
                .andExpect(jsonPath("$.row.missingToBuy[0].itemId").value(200))
                .andExpect(jsonPath("$.row.missingToBuy[0].itemName").value("Ingot"))
                .andExpect(jsonPath("$.row.missingToBuyOne[0].quantity").value(2));
    }

    @Test
    void theFreshRowCarriesTheSuppliedTotalSellValueAndTheTableRowsWholeFieldSet() throws Exception {
        // 4 crafts at 7890 revenue would be 31560; the fresh calculation says 9999. The detail route
        // states what that calculation produced, exactly as the table route does for its own.
        CraftResult row = new CraftResult(100, "Artificer", 4,
                new HashMap<>(Map.of(200, 5)), new HashMap<>(Map.of(200, 2)),
                1_234, 56, 7_890, 700, 2_800, 9_999, null, BlockedReason.NONE);
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                7, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED, row,
                new SingleCraftExplanation(7, 100, 2, true, rootCraftedByRequestedRecipe()),
                ITEMS, QUOTES));

        String body = mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.totalSellValueCopper").value(9999))
                .andReturn().getResponse().getContentAsString();

        // Every component of this fixture's row is populated, so the serialized object is the whole
        // shared projection - if the detail route ever stopped emitting one of the table's fields,
        // or gained one of its own, this comparison would say so.
        List<String> emitted = new ArrayList<>();
        JSON.readTree(body).path("row").fieldNames().forEachRemaining(emitted::add);
        List<String> declared = Arrays.stream(CraftingRowDto.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(declared, emitted);
    }

    // ---------- the recursive node shape (§13.3) ----------

    @Test
    void theRequestedRecipesOwnRootKeepsItsQuantitiesMethodsCostsAndIngredientChildren()
            throws Exception {
        factory.next(service -> service.canned = available(rootCraftedByRequestedRecipe()));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.itemId").value(100))
                .andExpect(jsonPath("$.tree.itemName").value("Widget"))
                .andExpect(jsonPath("$.tree.requestedQuantity").value(2))
                .andExpect(jsonPath("$.tree.inventoryQuantity").value(0))
                .andExpect(jsonPath("$.tree.craftedQuantity").value(2))
                .andExpect(jsonPath("$.tree.boughtQuantity").value(0))
                .andExpect(jsonPath("$.tree.missingQuantity").value(0))
                .andExpect(jsonPath("$.tree.recipeId").value(7))
                .andExpect(jsonPath("$.tree.craftCount").value(1))
                // Production may exceed the units used; it is not clamped to the requirement.
                .andExpect(jsonPath("$.tree.producedQuantity").value(2))
                .andExpect(jsonPath("$.tree.characterName").value("Aria"))
                .andExpect(jsonPath("$.tree.methods[0]").value("CRAFT"))
                .andExpect(jsonPath("$.tree.methods.length()").value(1))
                .andExpect(jsonPath("$.tree.recipeKnowledge").doesNotExist())
                .andExpect(jsonPath("$.tree.states.length()").value(0))
                .andExpect(jsonPath("$.tree.blockedReasons.length()").value(0))
                .andExpect(jsonPath("$.tree.cashCostCopper").value(36))
                .andExpect(jsonPath("$.tree.opportunityCostCopper").value(0))
                .andExpect(jsonPath("$.tree.effectiveCostCopper").value(36))
                .andExpect(jsonPath("$.tree.children.length()").value(1))
                .andExpect(jsonPath("$.tree.children[0].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[0].itemName").value("Ingot"))
                .andExpect(jsonPath("$.tree.children[0].requestedQuantity").value(3))
                .andExpect(jsonPath("$.tree.children[0].boughtQuantity").value(3))
                .andExpect(jsonPath("$.tree.children[0].methods[0]").value("BUY"))
                .andExpect(jsonPath("$.tree.children[0].recipeId").doesNotExist())
                .andExpect(jsonPath("$.tree.children[0].children.length()").value(0));
    }

    @Test
    void everyNodeCarriesItsOwnItemsIconRegardlessOfHowTheRequirementWasSourced() throws Exception {
        // STORY-API-009: a node's image follows its actual item identity (§13), never the requested
        // recipe's output and never the acquisition method. Owned stock, a bought ingredient and a
        // crafted sub-item are all still the item they are.
        CraftTraceNode root = node(100, 2).crafted(2).recipe(7, 1, 2).character("Aria")
                .methods(AcquisitionMethod.CRAFT).costs(36, 0, 36)
                .children(
                        node(200, 3).bought(3).methods(AcquisitionMethod.BUY).costs(36, 0, 36).build(),
                        node(300, 1).inventory(1).methods(AcquisitionMethod.INVENTORY).costs(0, 5, 5).build(),
                        node(400, 1).bought(1).methods(AcquisitionMethod.BUY).costs(7, 0, 7).build())
                .build();

        Map<Integer, ItemRepository.ItemInfo> items = Map.of(
                100, new ItemRepository.ItemInfo(100, "Widget", "C:\\icons\\items\\100.png", OUTPUT_SOURCE),
                200, new ItemRepository.ItemInfo(200, "Ingot", null, INGOT_SOURCE),
                300, new ItemRepository.ItemInfo(300, "Owned Bar", null, null),
                400, new ItemRepository.ItemInfo(400, "Scrap", null, "https://cdn.example.com/x.png"));

        factory.next(service -> service.canned = new CraftingResolutionDetail(
                7, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED,
                new CraftResult(100, "Artificer", 1, Map.of(), Map.of(), 36, 0, 100, 64, 64, null),
                new SingleCraftExplanation(7, 100, 2, true, root), items, QUOTES));

        String body = mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.itemId").value(100))
                .andExpect(jsonPath("$.tree.iconUrl").value(OUTPUT_ICON_URL))
                // the row on the same envelope uses the recipe's output item, which happens to be 100
                .andExpect(jsonPath("$.row.iconUrl").value(OUTPUT_ICON_URL))
                .andExpect(jsonPath("$.tree.children[0].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[0].iconUrl").value(INGOT_ICON_URL))
                // no retained metadata, and a rejected source: both null, and neither disturbs the node
                .andExpect(jsonPath("$.tree.children[1].itemId").value(300))
                .andExpect(jsonPath("$.tree.children[1].iconUrl").doesNotExist())
                .andExpect(jsonPath("$.tree.children[1].inventoryQuantity").value(1))
                .andExpect(jsonPath("$.tree.children[1].opportunityCostCopper").value(5))
                .andExpect(jsonPath("$.tree.children[2].itemId").value(400))
                .andExpect(jsonPath("$.tree.children[2].iconUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("C:\\icons"), "no backend filesystem path may reach the browser: " + body);
        assertFalse(body.contains("render.guildwars2.com"), "no upstream URL may reach the browser: " + body);
    }

    @Test
    void anInventoryOnlyRootHasNoRecipeNoCraftAndNoInventedIngredientPath() throws Exception {
        // The requested recipe's output came out of owned stock (KNOWN_PROBLEMS CH-15 shows through
        // here unchanged): the root must not be relabelled with the requested recipe id, wrapped in a
        // fabricated craft node, or given the recipe's ingredients.
        CraftTraceNode root = node(100, 2).inventory(2).costs(0, 480, 480)
                .methods(AcquisitionMethod.INVENTORY).build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                // The requested identity stays on the envelope only.
                .andExpect(jsonPath("$.recipeId").value(7))
                .andExpect(jsonPath("$.tree.recipeId").doesNotExist())
                .andExpect(jsonPath("$.tree.craftCount").value(0))
                .andExpect(jsonPath("$.tree.producedQuantity").value(0))
                .andExpect(jsonPath("$.tree.inventoryQuantity").value(2))
                .andExpect(jsonPath("$.tree.methods[0]").value("INVENTORY"))
                .andExpect(jsonPath("$.tree.children.length()").value(0))
                .andExpect(jsonPath("$.tree.characterName").doesNotExist())
                // A domain-established opportunity cost, not a suppressed or recomputed one.
                .andExpect(jsonPath("$.tree.opportunityCostCopper").value(480))
                .andExpect(jsonPath("$.tree.cashCostCopper").value(0));
    }

    @Test
    void anotherProducingRecipeKeepsItsOwnIdentityExecutionsAndChildren() throws Exception {
        // A different recipe supplied the requested output: its actual id, executions, production and
        // ingredients are retained rather than overwritten with the requested recipe's.
        CraftTraceNode root = node(100, 2).crafted(2).recipe(9, 1, 5).character("Bern")
                .methods(AcquisitionMethod.CRAFT).costs(70, 0, 70)
                .children(node(201, 4).bought(4).methods(AcquisitionMethod.BUY).costs(70, 0, 70).build())
                .build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId").value(7))
                .andExpect(jsonPath("$.tree.recipeId").value(9))
                .andExpect(jsonPath("$.tree.craftCount").value(1))
                // Batch size overshoot survives: 5 produced, 2 used.
                .andExpect(jsonPath("$.tree.producedQuantity").value(5))
                .andExpect(jsonPath("$.tree.craftedQuantity").value(2))
                .andExpect(jsonPath("$.tree.characterName").value("Bern"))
                .andExpect(jsonPath("$.tree.children[0].itemId").value(201));
    }

    @Test
    void aBlockedAttemptedRootRetainsItsRecipeWithoutClaimingCraftsAndHasNoCompleteCost()
            throws Exception {
        CraftTraceNode root = node(100, 2).missing(2)
                .recipe(7, 0, 0)
                .states(ResolutionState.BLOCKED)
                .blocked(BlockedReason.BUYING_DISABLED)
                .children(node(200, 3).missing(3)
                        .states(ResolutionState.BLOCKED)
                        .blocked(BlockedReason.BUYING_DISABLED, BlockedReason.NO_RECIPE)
                        .build())
                .build();
        factory.next(service -> service.canned = available(root, 0));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                // A blocked explanation is an available tree carrying its reasons, not a failure.
                .andExpect(jsonPath("$.treeStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.treeBasis").value("FIRST_BLOCKED_ATTEMPT"))
                .andExpect(jsonPath("$.row.craftableCount").value(0))
                .andExpect(jsonPath("$.tree.recipeId").value(7))
                .andExpect(jsonPath("$.tree.craftCount").value(0))
                .andExpect(jsonPath("$.tree.producedQuantity").value(0))
                .andExpect(jsonPath("$.tree.missingQuantity").value(2))
                .andExpect(jsonPath("$.tree.methods.length()").value(0))
                .andExpect(jsonPath("$.tree.states[0]").value("BLOCKED"))
                .andExpect(jsonPath("$.tree.blockedReasons[0]").value("BUYING_DISABLED"))
                // No misleading partial total for an unsatisfied requirement.
                .andExpect(jsonPath("$.tree.cashCostCopper").doesNotExist())
                .andExpect(jsonPath("$.tree.opportunityCostCopper").doesNotExist())
                .andExpect(jsonPath("$.tree.effectiveCostCopper").doesNotExist())
                // The attempted path's ingredients still explain why.
                .andExpect(jsonPath("$.tree.children[0].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[0].blockedReasons.length()").value(2))
                .andExpect(jsonPath("$.tree.children[0].blockedReasons[0]").value("BUYING_DISABLED"))
                .andExpect(jsonPath("$.tree.children[0].blockedReasons[1]").value("NO_RECIPE"));
    }

    @Test
    void repeatedOccurrencesOfOneItemStaySeparateAndKeepTheirOrder() throws Exception {
        CraftTraceNode first = node(200, 3).bought(3).methods(AcquisitionMethod.BUY).costs(36, 0, 36).build();
        CraftTraceNode second = node(200, 1).inventory(1).methods(AcquisitionMethod.INVENTORY)
                .costs(0, 10, 10).build();
        CraftTraceNode root = node(100, 2).crafted(2).recipe(7, 1, 2)
                .methods(AcquisitionMethod.CRAFT).costs(36, 10, 46)
                .children(first, second, node(300, 1).bought(1).methods(AcquisitionMethod.BUY)
                        .costs(5, 0, 5).build())
                .build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.children.length()").value(3))
                // Two occurrences of item 200, neither merged nor deduplicated, in resolver order.
                .andExpect(jsonPath("$.tree.children[0].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[0].requestedQuantity").value(3))
                .andExpect(jsonPath("$.tree.children[0].boughtQuantity").value(3))
                .andExpect(jsonPath("$.tree.children[1].itemId").value(200))
                .andExpect(jsonPath("$.tree.children[1].requestedQuantity").value(1))
                .andExpect(jsonPath("$.tree.children[1].inventoryQuantity").value(1))
                .andExpect(jsonPath("$.tree.children[2].itemId").value(300));
    }

    @Test
    void splitSourcingReportsEveryContributingMethodInOrder() throws Exception {
        CraftTraceNode root = node(100, 6).inventory(1).crafted(2).bought(2).missing(1)
                .recipe(7, 1, 2).character("Aria")
                .methods(AcquisitionMethod.INVENTORY, AcquisitionMethod.CRAFT, AcquisitionMethod.BUY)
                .states(ResolutionState.BLOCKED)
                .blocked(BlockedReason.INSUFFICIENT_BUDGET)
                .build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.methods.length()").value(3))
                .andExpect(jsonPath("$.tree.methods[0]").value("INVENTORY"))
                .andExpect(jsonPath("$.tree.methods[1]").value("CRAFT"))
                .andExpect(jsonPath("$.tree.methods[2]").value("BUY"))
                // States coexist with methods: a partially sourced requirement is still blocked.
                .andExpect(jsonPath("$.tree.states[0]").value("BLOCKED"))
                .andExpect(jsonPath("$.tree.blockedReasons[0]").value("INSUFFICIENT_BUDGET"))
                .andExpect(jsonPath("$.tree.inventoryQuantity").value(1))
                .andExpect(jsonPath("$.tree.craftedQuantity").value(2))
                .andExpect(jsonPath("$.tree.boughtQuantity").value(2))
                .andExpect(jsonPath("$.tree.missingQuantity").value(1));
    }

    @Test
    void knownZeroCostsPriceUnavailableAndUnvaluedNonTradeableAllSurviveDistinctly() throws Exception {
        CraftTraceNode unvalued = node(400, 1).inventory(1).methods(AcquisitionMethod.INVENTORY)
                .states(ResolutionState.UNVALUED_NONTRADEABLE)
                .costs(0, 0, 0)   // a domain-established zero, not an unknown
                .build();
        CraftTraceNode unpriced = node(500, 1).missing(1)
                .states(ResolutionState.BLOCKED, ResolutionState.PRICE_UNAVAILABLE)
                .blocked(BlockedReason.PRICE_UNAVAILABLE)
                .build();              // no complete value: null, never zero
        CraftTraceNode dailyLimited = node(600, 1).missing(1)
                .states(ResolutionState.DAILY_LIMIT)
                .blocked(BlockedReason.DAILY_LIMIT)
                .build();
        CraftTraceNode root = node(100, 2).crafted(2).recipe(7, 1, 2)
                .methods(AcquisitionMethod.CRAFT)
                .children(unvalued, unpriced, dailyLimited)
                .build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                // Known zero is present as 0 and keeps its explicit state.
                .andExpect(jsonPath("$.tree.children[0].cashCostCopper").value(0))
                .andExpect(jsonPath("$.tree.children[0].opportunityCostCopper").value(0))
                .andExpect(jsonPath("$.tree.children[0].effectiveCostCopper").value(0))
                .andExpect(jsonPath("$.tree.children[0].states[0]").value("UNVALUED_NONTRADEABLE"))
                // An unknown price is absent, never zero, and keeps both of its states.
                .andExpect(jsonPath("$.tree.children[1].cashCostCopper").doesNotExist())
                .andExpect(jsonPath("$.tree.children[1].effectiveCostCopper").doesNotExist())
                .andExpect(jsonPath("$.tree.children[1].states.length()").value(2))
                .andExpect(jsonPath("$.tree.children[1].states[0]").value("BLOCKED"))
                .andExpect(jsonPath("$.tree.children[1].states[1]").value("PRICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.tree.children[1].blockedReasons[0]").value("PRICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.tree.children[2].states[0]").value("DAILY_LIMIT"))
                .andExpect(jsonPath("$.tree.children[2].blockedReasons[0]").value("DAILY_LIMIT"));
    }

    @Test
    void anItemWithNoCapturedNameIsReportedWithoutOneRatherThanWithAFabricatedOne() throws Exception {
        CraftTraceNode root = node(100, 2).crafted(2).recipe(7, 1, 2)
                .methods(AcquisitionMethod.CRAFT)
                .children(node(999, 1).bought(1).methods(AcquisitionMethod.BUY).build())
                .build();
        factory.next(service -> service.canned = available(root));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.itemName").value("Widget"))
                .andExpect(jsonPath("$.tree.children[0].itemId").value(999))
                .andExpect(jsonPath("$.tree.children[0].itemName").doesNotExist());
    }

    @Test
    void aFiniteBlockedCycleNodeIsSerializedWithoutAnyGraphBackLink() throws Exception {
        CraftTraceNode root = node(100, 2).missing(2).recipe(7, 0, 0)
                .states(ResolutionState.BLOCKED)
                .blocked(BlockedReason.CYCLE_DETECTED)
                .children(node(200, 3).missing(3)
                        .states(ResolutionState.BLOCKED)
                        .blocked(BlockedReason.CYCLE_DETECTED)
                        .build())
                .build();
        factory.next(service -> service.canned = available(root));

        String body = mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.blockedReasons[0]").value("CYCLE_DETECTED"))
                .andExpect(jsonPath("$.tree.children[0].children.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.length() < 4_000, "a cycle must terminate, not serialize a repeating structure");
    }

    // ---------- §13.4 outcomes ----------

    @Test
    void anUnavailableCalculationResultIsACompleted200WithNoTree() throws Exception {
        CraftResult row = new CraftResult(100, "Artificer", 0,
                Map.of(), Map.of(), 0, 0, 0, 0, 0, null, BlockedReason.PRICE_UNAVAILABLE);
        factory.next(service -> service.canned = new CraftingResolutionDetail(
                7, CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, REQUESTED, row,
                SingleCraftExplanation.unavailable(7, 100, 2), ITEMS, QUOTES));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeStatus").value("RESULT_UNAVAILABLE"))
                // Not a fabricated empty tree, and the row is still reported.
                .andExpect(jsonPath("$.tree").doesNotExist())
                .andExpect(jsonPath("$.treeBasis").value("SELECTED_RESULT_OUTPUT_QUANTITY"))
                .andExpect(jsonPath("$.row.recipeId").value(7))
                .andExpect(jsonPath("$.row.blockedReason").value("PRICE_UNAVAILABLE"));
    }

    @Test
    void aRecipeOutsideThisCalculationIsA404WithTheDocumentedErrorBody() throws Exception {
        factory.next(service -> service.canned =
                notInCalculationDetail(4_242));

        mockMvc.perform(resolution("{\"recipeId\": 4242, \"calculation\": {}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECIPE_NOT_IN_CALCULATION"))
                .andExpect(jsonPath("$.message")
                        .value("Recipe 4242 is not part of this calculation's visible recipes"));

        assertEquals(4_242, factory.only().capturedRecipeId,
                "the 404 must come from the calculation's own candidate set, not from a pre-check");
    }

    // ---------- rejected requests ----------

    @Test
    void anAbsentBodyIsRejectedWithoutCreatingAService() throws Exception {
        mockMvc.perform(post("/api/crafting/profit/resolution"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("a request body is required: it must carry recipeId and calculation"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void anAbsentRecipeIdIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(resolution("{\"calculation\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "recipeId is required: resolution detail explains one explicitly selected recipe"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void aNonPositiveRecipeIdIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(resolution("{\"recipeId\": 0, \"calculation\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("recipeId must be a positive integer"));

        mockMvc.perform(resolution("{\"recipeId\": -7, \"calculation\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("recipeId must be a positive integer"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void anAbsentCalculationIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(resolution("{\"recipeId\": 7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "calculation is required: it carries the scope and settings the recipe is resolved in"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void theNestedCalculationIsValidatedByTheTableRoutesOwnRules() throws Exception {
        mockMvc.perform(resolution("""
                        {"recipeId": 7, "calculation": {"scope": {"kind": "EVERYTHING"}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "scope.kind must be one of ALL, DISCIPLINE, CHARACTER_DISCIPLINE"));

        mockMvc.perform(resolution("""
                        {"recipeId": 7, "calculation": {"scope": {"kind": "DISCIPLINE"}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("scope.discipline is required for scope.kind DISCIPLINE"));

        mockMvc.perform(resolution("""
                        {"recipeId": 7, "calculation": {"settings": {"maxBuyCopper": -1}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("settings.maxBuyCopper must not be negative"));

        assertEquals(0, factory.created.size(), "an invalid request must not create or call the service");
    }

    @Test
    void malformedJsonIsRejectedWithoutStartingACalculation() throws Exception {
        mockMvc.perform(resolution("{\"recipeId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertEquals(0, factory.created.size());
    }

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        factory.next(service -> service.failure =
                new SQLException("connection to jdbc:postgresql://host/db refused as user gw2"));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"));
    }

    @Test
    void calculationFailureIsMappedToInternalServerErrorWithoutLeakingDetail() throws Exception {
        factory.next(service -> service.failure =
                new RuntimeException("Failed to load crafting graph cache"));

        mockMvc.perform(resolution("{\"recipeId\": 7, \"calculation\": {}}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("CALCULATION_FAILED"))
                .andExpect(jsonPath("$.message").value("The crafting calculation could not be completed"));
    }

    // ---------- request isolation ----------

    @Test
    void successiveRequestsEachGetTheirOwnServiceAndTheirOwnDetail() throws Exception {
        factory.deriveResultFromRequest = true;

        mockMvc.perform(resolution("{\"recipeId\": 11, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId").value(11))
                .andExpect(jsonPath("$.tree.recipeId").value(11))
                .andExpect(jsonPath("$.row.recipeId").value(11));

        mockMvc.perform(resolution("{\"recipeId\": 22, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId").value(22))
                .andExpect(jsonPath("$.tree.recipeId").value(22))
                .andExpect(jsonPath("$.row.recipeId").value(22));

        assertEquals(2, factory.created.size());
        assertNotSame(factory.created.get(0), factory.created.get(1),
                "each request must run on its own service instance, so no calculation state is shared");
        assertEquals(1, factory.created.get(0).detailCalls);
        assertEquals(1, factory.created.get(1).detailCalls);
    }

    @Test
    void concurrentRequestsWithDistinctRecipesDoNotMixDetails() throws Exception {
        factory.deriveResultFromRequest = true;
        int requests = 8;

        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int recipeId = 100 + i;
            calls.add(() -> mockMvc.perform(resolution(
                            "{\"recipeId\": " + recipeId + ", \"calculation\": {}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipeId").value(recipeId))
                    .andExpect(jsonPath("$.tree.recipeId").value(recipeId))
                    .andExpect(jsonPath("$.tree.itemId").value(recipeId))
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
        Map<StubProfitService, Boolean> identity = new java.util.IdentityHashMap<>();
        for (StubProfitService instance : factory.created) identity.put(instance, Boolean.TRUE);
        assertEquals(requests, identity.size(), "concurrent requests must not share a service instance");
    }

    @Test
    void aNotInCalculationOutcomeCannotExposeAnEarlierRequestsTree() throws Exception {
        factory.deriveResultFromRequest = true;

        mockMvc.perform(resolution("{\"recipeId\": 11, \"calculation\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree.recipeId").value(11));

        // 99 is the stub's "outside the candidate set" recipe: the response must carry no tree at all.
        String body = mockMvc.perform(resolution("{\"recipeId\": 99, \"calculation\": {}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECIPE_NOT_IN_CALCULATION"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"tree\""), "a 404 must not carry any tree: " + body);
        assertFalse(body.contains("\"row\""), "a 404 must not carry any row: " + body);
    }

    // ---------- fixtures ----------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            resolution(String body) {
        return post("/api/crafting/profit/resolution")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** The requested recipe resolved by crafting it: one execution, ingredients bought. */
    private static CraftTraceNode rootCraftedByRequestedRecipe() {
        return node(100, 2).crafted(2).recipe(7, 1, 2).character("Aria")
                .methods(AcquisitionMethod.CRAFT).costs(36, 0, 36)
                .children(node(200, 3).bought(3).methods(AcquisitionMethod.BUY).costs(36, 0, 36).build())
                .build();
    }

    private static CraftingResolutionDetail available(CraftTraceNode root) {
        return available(root, 1);
    }

    private static CraftingResolutionDetail available(CraftTraceNode root, int craftableCount) {
        CraftResult row = new CraftResult(100, "Artificer", craftableCount,
                Map.of(), Map.of(), 36, 0, 100, 64, 64, null);
        return new CraftingResolutionDetail(
                7, CraftingResolutionDetail.Status.AVAILABLE, REQUESTED, row,
                new SingleCraftExplanation(7, 100, 2, true, root), ITEMS, QUOTES);
    }

    private static CraftingResolutionDetail notInCalculationDetail(int recipeId) {
        return new CraftingResolutionDetail(
                recipeId, CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION,
                null, null, null, Map.of(), Map.of());
    }

    private static NodeBuilder node(int itemId, int requestedQuantity) {
        return new NodeBuilder(itemId, requestedQuantity);
    }

    /**
     * Builds one {@link CraftTraceNode} fixture. The domain record has seventeen components and the
     * cases above differ in only a few each, so this keeps every test's intent - which quantities,
     * which identity, which states - readable.
     */
    private static final class NodeBuilder {
        private final int itemId;
        private final int requestedQuantity;
        private int inventory;
        private int crafted;
        private int bought;
        private int missing;
        private Integer recipeId;
        private int craftCount;
        private int produced;
        private String characterName;
        private List<AcquisitionMethod> methods = List.of();
        private List<ResolutionState> states = List.of();
        private List<BlockedReason> blockedReasons = List.of();
        private Integer cash;
        private Integer opportunity;
        private Integer effective;
        private List<CraftTraceNode> children = List.of();

        private NodeBuilder(int itemId, int requestedQuantity) {
            this.itemId = itemId;
            this.requestedQuantity = requestedQuantity;
        }

        NodeBuilder inventory(int quantity) { this.inventory = quantity; return this; }
        NodeBuilder crafted(int quantity) { this.crafted = quantity; return this; }
        NodeBuilder bought(int quantity) { this.bought = quantity; return this; }
        NodeBuilder missing(int quantity) { this.missing = quantity; return this; }

        NodeBuilder recipe(int recipeId, int craftCount, int produced) {
            this.recipeId = recipeId;
            this.craftCount = craftCount;
            this.produced = produced;
            return this;
        }

        NodeBuilder character(String name) { this.characterName = name; return this; }
        NodeBuilder methods(AcquisitionMethod... values) { this.methods = List.of(values); return this; }
        NodeBuilder states(ResolutionState... values) { this.states = List.of(values); return this; }
        NodeBuilder blocked(BlockedReason... values) { this.blockedReasons = List.of(values); return this; }

        NodeBuilder costs(Integer cash, Integer opportunity, Integer effective) {
            this.cash = cash;
            this.opportunity = opportunity;
            this.effective = effective;
            return this;
        }

        NodeBuilder children(CraftTraceNode... values) { this.children = List.of(values); return this; }

        CraftTraceNode build() {
            return new CraftTraceNode(itemId, requestedQuantity, inventory, crafted, bought, missing,
                    recipeId, craftCount, produced, characterName, methods, states, blockedReasons,
                    cash, opportunity, effective, children);
        }
    }

    // ---------- stubs ----------

    /**
     * Stands in for the application service: records what the controller handed it and returns a
     * canned detail (or fails), so these tests never touch a database or a real calculation.
     */
    private static final class StubProfitService extends CraftingProfitService {
        int capturedRecipeId;
        DiscChoice capturedChoice;
        CraftingSettings capturedSettings;
        int detailCalls;
        int reloadCalls;
        CraftingResolutionDetail canned;
        Exception failure;
        boolean deriveResultFromRequest;

        StubProfitService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
            reloadCalls++;
            return new ProfitData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }

        @Override
        public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                     CraftingSettings settings) throws SQLException {
            detailCalls++;
            this.capturedRecipeId = recipeId;
            this.capturedChoice = choice;
            this.capturedSettings = settings;

            if (failure instanceof SQLException sqlException) throw sqlException;
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;

            if (!deriveResultFromRequest) return canned;

            // Detail derived from this request's own recipe id, so a response carrying another
            // request's data would show up as a mismatched id. 99 stands for "not in this
            // calculation's candidate set".
            if (recipeId == 99) return notInCalculationDetail(recipeId);

            try {
                Thread.sleep(5); // widen the window in which concurrent requests overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Recipe recipe = new Recipe(recipeId, recipeId, 1, 0, "Chef", List.of());
            CraftTraceNode root = node(recipeId, 1).crafted(1).recipe(recipeId, 1, 1)
                    .methods(AcquisitionMethod.CRAFT).costs(1, 0, 1).build();
            return new CraftingResolutionDetail(
                    recipeId, CraftingResolutionDetail.Status.AVAILABLE, recipe,
                    new CraftResult(recipeId, "Chef", 1, Map.of(), Map.of(), 1, 0, 2, 1, 1, null),
                    new SingleCraftExplanation(recipeId, recipeId, 1, true, root),
                    Map.of(recipeId, new ItemRepository.ItemInfo(recipeId, "Item " + recipeId, null, null)),
                    Map.of());
        }
    }

    /** The controller's per-request service seam, recording every instance it hands out. */
    private static final class RecordingServiceFactory implements Supplier<CraftingProfitService> {
        final List<StubProfitService> created = Collections.synchronizedList(new ArrayList<>());
        private Consumer<StubProfitService> configure = service -> {};
        boolean deriveResultFromRequest;

        void next(Consumer<StubProfitService> configure) {
            this.configure = configure;
        }

        StubProfitService only() {
            assertEquals(1, created.size(), "expected exactly one service instance");
            return created.get(0);
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
