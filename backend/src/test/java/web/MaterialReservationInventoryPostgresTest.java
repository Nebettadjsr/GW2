package web;

import application.CachedCraftingProfitService;
import application.CraftingProfitService;
import application.ProfitInputSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.Db;
import repo.DiscChoice;
import repo.EnvConfig;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #235: existing HTTP/service seams drive the real inventory SQL and planner in a disposable schema. */
class MaterialReservationInventoryPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AccountScope A = AccountScope.of(AuthorizedRequests.ACCOUNT_ID);
    private static final AccountScope B = AccountScope.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private final String schema = "test_reservations_" + UUID.randomUUID().toString().replace("-", "");
    private List<Recipe> recipes = List.of(new Recipe(7, 100, 1, 0, "Artificer", List.of(new Ingredient(200, 1))));
    private Connection admin;
    private MockMvc mvc;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        execute("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        execute("INSERT INTO account_registry (account_id) VALUES ('" + A.accountId() + "'), ('" + B.accountId() + "')",
                "INSERT INTO account_bank (account_id, slot, item_id, count) VALUES"
                        + " ('" + A.accountId() + "', 0, 200, 200), ('" + A.accountId() + "', 1, 200, 100),"
                        + " ('" + B.accountId() + "', 0, 200, 999)",
                "INSERT INTO account_materials (account_id, item_id, count) VALUES"
                        + " ('" + A.accountId() + "', 200, 40), ('" + B.accountId() + "', 200, 777)",
                "INSERT INTO characters (account_id, character_id, name) VALUES"
                        + " ('" + A.accountId() + "', 1, 'Aria'), ('" + A.accountId() + "', 2, 'Bran')",
                "INSERT INTO character_items (account_id, character_id, location, item_id, count) VALUES"
                        + " ('" + A.accountId() + "', 1, 'BAG', 200, 10)");
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
        mvc = MockMvcBuilders.standaloneSetup(new CraftingProfitApiController(new LocalCalculation(),
                        new web.access.AccountAccessHttp("")))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (admin == null) return;
        try { execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { admin.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"protectedBankQuantities", "protectedMaterialQuantities"})
    void threeHundredLessTwoHundredAndFiftyLeavesFiftyPlusOtherStoreAndCharacterStock(String field)
            throws Exception {
        if (field.equals("protectedMaterialQuantities")) {
            execute("UPDATE account_bank SET count = 20 WHERE account_id = '" + A.accountId() + "'",
                    "UPDATE account_materials SET count = 300 WHERE account_id = '" + A.accountId() + "'");
        }
        String settings = finite(field, 250);
        JsonNode table = calculate(false, settings, null);
        JsonNode detail = calculate(true, settings, null);
        assertEquals(100, table.get("rows").get(0).get("craftableCount").asInt(),
                "50 from the reserved source + 40 from the other store + 10 from the character");
        assertEquals(table.get("rows").get(0), detail.get("row"));
        assertEquals(100, detail.get("tree").get("inventoryQuantity").asInt()
                + detail.get("tree").get("children").get(0).get("inventoryQuantity").asInt());
        assertEquals(JSON.readTree("{\"200\":250}"), table.get("settings").get(field));
        assertEquals(350, new InventoryRepository().loadOwnedInventory(admin, A).get(200),
                "no cached quantity was edited by either calculation");
        assertEquals(1776, new InventoryRepository().loadOwnedInventory(admin, B).get(200));
    }

    @ParameterizedTest
    @CsvSource({"200,50", "300,100", "400,200"})
    void savedTargetRemainsFixedWhenStockFallsBelowItOrGrows(int bankStock, int expectedCrafts) throws Exception {
        execute("UPDATE account_bank SET count = CASE WHEN slot = 0 THEN " + (bankStock - 100)
                + " ELSE 100 END WHERE account_id = '" + A.accountId() + "'");
        JsonNode result = calculate(false, finite("protectedBankQuantities", 250), null);
        assertEquals(expectedCrafts, result.get("rows").get(0).get("craftableCount").asInt());
        assertEquals(250, result.get("settings").get("protectedBankQuantities").get("200").asInt());
    }

    @Test
    void bothStoreTargetsApplyIndependentlyAndNeverMaskCharacterStockGlobally() throws Exception {
        JsonNode result = calculate(false, """
                {"useOwnMats":true,"allowBuying":false,"materialProtectionEnabled":true,
                 "protectedItemIds":[],"protectedBankQuantities":{"200":250},
                 "protectedMaterialQuantities":{"200":25}}
                """, null);
        assertEquals(75, result.get("rows").get(0).get("craftableCount").asInt());
        assertTrue(result.get("settings").get("protectedItemIds").isEmpty());
    }

    @Test
    void maximumTargetReservesAtMostOwnedStockWithoutOverflowOrAQuantityCap() throws Exception {
        JsonNode result = calculate(false, finite("protectedBankQuantities", Integer.MAX_VALUE), null);
        assertEquals(50, result.get("rows").get(0).get("craftableCount").asInt());
        assertEquals(Integer.MAX_VALUE,
                result.get("settings").get("protectedBankQuantities").get("200").asInt());
    }

    @Test
    void disabledFiniteProtectionPreservesTheUnprotectedResult() throws Exception {
        JsonNode baseline = calculate(false, "{\"useOwnMats\":true,\"allowBuying\":false}", null);
        JsonNode disabled = calculate(false, """
                {"useOwnMats":true,"allowBuying":false,"materialProtectionEnabled":false,
                 "protectedItemIds":[],"protectedBankQuantities":{"200":250}}
                """, null);
        assertEquals(baseline.get("rows"), disabled.get("rows"));
    }

    @Test
    void homogeneousOwnerBindingStillRestrictsWhoCanUseTheUnreservedBankCopies() throws Exception {
        execute("UPDATE account_bank SET binding = 'Character', bound_to = 'Aria'"
                + " WHERE account_id = '" + A.accountId() + "'");
        String settings = finite("protectedBankQuantities", 250);
        assertEquals(100, calculate(false, settings, "Aria").get("rows").get(0).get("craftableCount").asInt());
        assertEquals(50, calculate(false, settings, "Bran").get("rows").get(0).get("craftableCount").asInt());
    }

    @Test
    void replacementPurchasesRespectBudgetAndMatchTableDetailAndShoppingQuantities() throws Exception {
        String settings = """
                {"useOwnMats":true,"allowBuying":true,"maxBuyCopper":6,"materialProtectionEnabled":true,
                 "protectedItemIds":[],"protectedBankQuantities":{"200":250}}
                """;
        JsonNode row = calculate(false, settings, null).get("rows").get(0);
        JsonNode detail = calculate(true, settings, null);
        assertEquals(102, row.get("craftableCount").asInt());
        assertEquals(6, row.get("buyCostCopper").asInt());
        assertEquals(row, detail.get("row"));
        assertEquals(2, row.get("missingToBuy").get(0).get("quantity").asInt());
        assertEquals(100, detail.get("tree").get("children").get(0).get("inventoryQuantity").asInt());
        assertEquals(2, detail.get("tree").get("children").get(0).get("boughtQuantity").asInt());
    }

    @Test
    void adjustedRawStockStillFeedsRecursiveCraftingAndProducedSurplus() throws Exception {
        recipes = List.of(new Recipe(7, 100, 1, 0, "Artificer", List.of(new Ingredient(201, 1))),
                new Recipe(8, 201, 2, 0, "Artificer", List.of(new Ingredient(200, 10))));
        JsonNode rows = calculate(false, finite("protectedBankQuantities", 250), null).get("rows");
        JsonNode root = rows.get(0).get("recipeId").asInt() == 7 ? rows.get(0) : rows.get(1);
        assertEquals(20, root.get("craftableCount").asInt(), "10 intermediate batches yield 20 usable outputs");
    }

    @Test
    void changingOnlyTheTargetRecalculatesDetailAndDoesNotContaminateTheEarlierTableContext() throws Exception {
        JsonNode original = calculate(false, finite("protectedBankQuantities", 250), null);
        JsonNode changedDetail = calculate(true, finite("protectedBankQuantities", 200), null);
        JsonNode repeated = calculate(false, finite("protectedBankQuantities", 250), null);

        assertEquals(100, original.get("rows").get(0).get("craftableCount").asInt());
        assertEquals(150, changedDetail.get("row").get("craftableCount").asInt());
        assertEquals(150, changedDetail.get("tree").get("children").get(0).get("inventoryQuantity").asInt());
        assertEquals(200, changedDetail.get("calculation").get("settings")
                .get("protectedBankQuantities").get("200").asInt());
        assertEquals(original.get("rows"), repeated.get("rows"));
        assertEquals(original.get("settings"), repeated.get("settings"));
        assertEquals(350, new InventoryRepository().loadOwnedInventory(admin, A).get(200));
    }

    private static String finite(String field, int quantity) {
        return "{\"useOwnMats\":true,\"allowBuying\":false,\"materialProtectionEnabled\":true,"
                + "\"protectedItemIds\":[],\"" + field + "\":{\"200\":" + quantity + "}}";
    }

    private JsonNode calculate(boolean detail, String settings, String character) throws Exception {
        String scope = character == null ? "{\"kind\":\"ALL\"}" : "{\"kind\":\"CHARACTER_DISCIPLINE\","
                + "\"discipline\":\"Artificer\",\"characterName\":\"" + character + "\",\"rating\":400}";
        String calculation = "{\"scope\":" + scope + ",\"settings\":" + settings + "}";
        String request = detail ? "{\"recipeId\":7,\"calculation\":" + calculation + "}" : calculation;
        String body = mvc.perform(post(detail ? "/api/crafting/profit/resolution" : "/api/crafting/profit")
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private void execute(String... sql) throws Exception {
        try (Statement statement = admin.createStatement()) {
            for (String query : sql) statement.execute(query);
        }
    }

    /** Only capture/refresh infrastructure is replaced; inventory SQL, service and calculations are real. */
    private final class LocalCalculation extends CachedCraftingProfitService {
        private LocalCalculation() { super(null, null, null, null, null, null, null, null, null); }

        @Override
        public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings) throws Exception {
            try (ProfitInputSession session = ProfitInputSession.open(scope)) {
                return new ProfitOutcome(service(session).reload(choice, settings), null);
            }
        }

        @Override
        public ResolutionOutcome resolution(AccountScope scope, int recipeId, DiscChoice choice,
                                             CraftingSettings settings, String basedOnCalculationId) throws Exception {
            try (ProfitInputSession session = ProfitInputSession.open(scope)) {
                return new ResolutionOutcome(service(session).resolveDetail(recipeId, choice, settings),
                        CONSISTENCY_FRESH_CALCULATION, null);
            }
        }
    }

    private CraftingProfitService service(ProfitInputSession session) {
        RecipeRepository recipeRepository = new RecipeRepository() {
            @Override public List<Recipe> loadRecipes(Connection con, AccountScope scope, String discipline) {
                return recipes;
            }
            @Override public List<Recipe> loadRecipesForCharacter(Connection con, AccountScope scope,
                                                                  String character, String discipline) {
                return recipes;
            }
        };
        ItemRepository items = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> ids) { return Map.of(); }
        };
        TpPriceRepository prices = new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> ids) {
                return Map.of(100, new PriceQuote(1000, 1000), 200, new PriceQuote(2, 3));
            }
        };
        CharacterRepository characters = new CharacterRepository() {
            @Override public List<DiscRow> loadAllCharacterCrafting(Connection con, AccountScope scope) {
                return List.of(new DiscRow("Aria", "Artificer", 400, true), new DiscRow("Bran", "Artificer", 400, true));
            }
        };
        CraftingGraphCache graph = new CraftingGraphCache(recipeRepository) {
            @Override public CraftingGraph loadForSnapshot(Connection con) { return new CraftingGraph(recipes); }
        };
        return new CraftingProfitService(recipeRepository, new InventoryRepository(), prices, items,
                characters, graph, new CraftingPlanner(), session);
    }
}
