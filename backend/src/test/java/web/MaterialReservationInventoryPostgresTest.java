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
import java.sql.PreparedStatement;
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
    private Map<Integer, PriceQuote> quotes = Map.of(100, new PriceQuote(1000, 1000), 200, new PriceQuote(2, 3));
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

    @ParameterizedTest
    @CsvSource({"250,75,750", "300,70,700"})
    void fourBankStacksKeepTheFixedTargetExactlyOnce(int target, int crafts, int consumed) throws Exception {
        tenUnitRecipe();
        replaceBank(List.of(new BankStack(30, 200, 250, null, null), new BankStack(9, 200, 250, null, null),
                new BankStack(4, 200, 250, null, null), new BankStack(0, 200, 250, null, null)));
        execute("DELETE FROM account_materials WHERE account_id = '" + A.accountId() + "'",
                "DELETE FROM character_items WHERE account_id = '" + A.accountId() + "'");
        String settings = finite("protectedBankQuantities", target);

        JsonNode table = calculate(false, settings, null);
        JsonNode detail = calculate(true, settings, null);

        assertEquals(crafts, row(table, 7).get("craftableCount").asInt());
        assertEquals(row(table, 7), detail.get("row"));
        assertEquals(consumed, detail.get("tree").get("children").get(0).get("inventoryQuantity").asInt());
        assertEquals(1000, new InventoryRepository().loadOwnedInventory(admin, A).get(200));
    }

    @ParameterizedTest
    @CsvSource({"250,55,55,80", "300,50,55,75"})
    void numericModeUsesSlotOrderBeforeBindingAndRosterEligibility(int target, int aria, int bran, int all)
            throws Exception {
        mixedBank();
        String settings = finite("protectedBankQuantities", target);

        assertEquals(aria, row(calculate(false, settings, "Aria"), 7).get("craftableCount").asInt());
        assertEquals(bran, row(calculate(false, settings, "Bran"), 7).get("craftableCount").asInt());
        assertEquals(all, row(calculate(false, settings, null), 7).get("craftableCount").asInt());
    }

    @Test
    void validManualThirdStackProtectsItsActualOwnerAndKeepsOtherSourcesUsable() throws Exception {
        List<BankStack> observed = mixedBank();
        String settings = manualSettings(250, observed, Map.of(9, 250));

        JsonNode table = calculate(false, settings, "Aria");
        JsonNode detail = calculate(true, settings, "Aria");

        assertEquals(80, row(table, 7).get("craftableCount").asInt(),
                "the selected Bran stack is already unusable by Aria; do not reserve another eligible stack");
        assertEquals(55, row(calculate(false, settings, "Bran"), 7).get("craftableCount").asInt());
        assertEquals(row(table, 7), detail.get("row"));
        assertEquals(800, detail.get("tree").get("children").get(0).get("inventoryQuantity").asInt());
        assertEquals(JSON.readTree(settings).get("protectedBankAllocations"),
                table.get("settings").get("protectedBankAllocations"));
        assertEquals(1050, new InventoryRepository().loadOwnedInventory(admin, A).get(200));
        assertEquals(1776, new InventoryRepository().loadOwnedInventory(admin, B).get(200));
    }

    @ParameterizedTest
    @CsvSource({"250,50,0,300,75", "0,50,0,50,100", "250,50,250,550,50"})
    void manualRowMutationsChangeTheFixedTotalWithoutCountingCopiesTwice(int first, int second, int third,
                                                                         int target, int crafts) throws Exception {
        List<BankStack> observed = mixedBank();
        String settings = manualSettings(target, observed, Map.of(0, first, 4, second, 9, third));

        JsonNode table = calculate(false, settings, null);
        JsonNode detail = calculate(true, settings, null);

        assertEquals(crafts, row(table, 7).get("craftableCount").asInt());
        assertEquals(row(table, 7), detail.get("row"));
        assertEquals(target, table.get("settings").get("protectedBankQuantities").get("200").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "split", "zero-count-change", "extra-row", "missing-row",
            "incomplete-zero-guards", "binding-swap", "owner-swap"})
    void staleManualGroupsReflowTheWholeTargetUsingCurrentRowsAndTrustedBindings(String change) throws Exception {
        List<BankStack> observed = mixedBank();
        String character = "Aria";
        int expected = 55;
        switch (change) {
            case "move" -> execute("UPDATE account_bank SET slot = 10 WHERE account_id = '" + A.accountId()
                    + "' AND slot = 9");
            case "split" -> {
                execute("UPDATE account_bank SET count = 100 WHERE account_id = '" + A.accountId() + "' AND slot = 9",
                        "INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES ('"
                                + A.accountId() + "', 10, 200, 150, 'Character', 'Bran')");
            }
            case "zero-count-change" -> {
                execute("UPDATE account_bank SET count = 240 WHERE account_id = '" + A.accountId() + "' AND slot = 30");
                expected = 54;
            }
            case "extra-row" -> {
                execute("INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES ('"
                        + A.accountId() + "', 31, 200, 250, 'Character', 'Aria')");
                expected = 80;
            }
            case "missing-row" -> {
                execute("DELETE FROM account_bank WHERE account_id = '" + A.accountId() + "' AND slot = 30");
                expected = 30;
            }
            case "incomplete-zero-guards" -> observed = observed.stream().filter(s -> s.slot() != 30).toList();
            case "binding-swap" -> {
                execute("UPDATE account_bank SET binding = CASE WHEN slot = 0 THEN 'Character' ELSE 'Account' END,"
                        + " bound_to = CASE WHEN slot = 0 THEN 'Bran' ELSE NULL END WHERE account_id = '"
                        + A.accountId() + "' AND slot IN (0, 9)");
                expected = 80;
            }
            case "owner-swap" -> {
                execute("UPDATE account_bank SET bound_to = CASE WHEN slot = 4 THEN 'Bran' ELSE 'Aria' END"
                        + " WHERE account_id = '" + A.accountId() + "' AND slot IN (4, 9)");
                character = "Bran";
            }
            default -> throw new AssertionError(change);
        }
        String settings = manualSettings(250, observed, Map.of(9, 250));

        JsonNode table = calculate(false, settings, character);
        JsonNode detail = calculate(true, settings, character);

        assertEquals(expected, row(table, 7).get("craftableCount").asInt(), change);
        assertEquals(row(table, 7), detail.get("row"));
        assertEquals(250, table.get("settings").get("protectedBankQuantities").get("200").asInt());
        assertEquals(JSON.readTree(settings).get("protectedBankAllocations"),
                table.get("settings").get("protectedBankAllocations"), "echo keeps canonical original intent");
    }

    @Test
    void replacedItemIsNeverProtectedAndTheMissingItemsTargetSurvives() throws Exception {
        List<BankStack> observed = mixedBank();
        execute("UPDATE account_bank SET item_id = 201, binding = NULL, bound_to = NULL WHERE account_id = '"
                + A.accountId() + "' AND slot = 9");
        recipes = List.of(recipes.getFirst(), new Recipe(8, 101, 1, 0, "Artificer", List.of(new Ingredient(201, 10))));
        quotes = Map.of(100, new PriceQuote(1000, 1000), 101, new PriceQuote(1000, 1000),
                200, new PriceQuote(2, 3), 201, new PriceQuote(2, 3));
        String settings = manualSettings(250, observed, Map.of(9, 250));

        JsonNode result = calculate(false, settings, "Aria");

        assertEquals(55, row(result, 7).get("craftableCount").asInt());
        assertEquals(25, row(result, 8).get("craftableCount").asInt(), "replacement item retains all its copies");
        execute("DELETE FROM account_bank WHERE account_id = '" + A.accountId() + "' AND item_id = 200");
        JsonNode absent = calculate(false, settings, "Aria");
        assertEquals(5, row(absent, 7).get("craftableCount").asInt());
        assertEquals(25, row(absent, 8).get("craftableCount").asInt());
        assertEquals(250, absent.get("settings").get("protectedBankQuantities").get("200").asInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleHintsRetainTheFixedTargetWhenStockFallsBelowItOrGrows(boolean grows) throws Exception {
        List<BankStack> observed = mixedBank();
        if (grows) {
            execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('"
                    + A.accountId() + "', 40, 200, 500)");
        } else {
            replaceBank(List.of(new BankStack(0, 200, 100, "Account", null),
                    new BankStack(4, 200, 100, "Character", "Aria")));
        }
        JsonNode result = calculate(false, manualSettings(250, observed, Map.of(9, 250)), "Aria");
        assertEquals(grows ? 105 : 5, row(result, 7).get("craftableCount").asInt());
        assertEquals(250, result.get("settings").get("protectedBankQuantities").get("200").asInt());
    }

    @Test
    void changingOnlyManualAllocationsOrClearingThemDoesNotReuseAnotherDetailContext() throws Exception {
        List<BankStack> observed = mixedBank();
        String third = manualSettings(250, observed, Map.of(9, 250));
        String first = manualSettings(250, observed, Map.of(0, 250));

        JsonNode original = calculate(false, third, "Aria");
        JsonNode changed = calculate(true, first, "Aria");
        JsonNode numeric = calculate(true, finite("protectedBankQuantities", 250), "Aria");
        JsonNode repeated = calculate(false, third, "Aria");

        assertEquals(80, row(original, 7).get("craftableCount").asInt());
        assertEquals(55, changed.get("row").get("craftableCount").asInt());
        assertEquals(55, numeric.get("row").get("craftableCount").asInt());
        assertEquals(JSON.readTree(first).get("protectedBankAllocations"),
                changed.get("calculation").get("settings").get("protectedBankAllocations"));
        assertEquals(JSON.readTree("[]"), numeric.get("calculation").get("settings").get("protectedBankAllocations"));
        assertEquals(original.get("rows"), repeated.get("rows"));
    }

    @Test
    void manualProtectionPreservesBudgetedReplacementBuyingAndRecursiveSurplus() throws Exception {
        List<BankStack> observed = mixedBank();
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(
                manualSettings(250, observed, Map.of(9, 250)));
        settings.put("allowBuying", true).put("maxBuyCopper", 30);
        JsonNode bought = calculate(false, settings.toString(), "Aria");
        JsonNode detail = calculate(true, settings.toString(), "Aria");
        assertEquals(81, row(bought, 7).get("craftableCount").asInt());
        assertEquals(30, row(bought, 7).get("buyCostCopper").asInt());
        assertEquals(10, row(bought, 7).get("missingToBuy").get(0).get("quantity").asInt());
        assertEquals(row(bought, 7), detail.get("row"));
        recipes = List.of(new Recipe(7, 100, 1, 0, "Artificer", List.of(new Ingredient(201, 1))),
                new Recipe(8, 201, 2, 0, "Artificer", List.of(new Ingredient(200, 10))));
        assertEquals(160, row(calculate(false, manualSettings(250, observed, Map.of(9, 250)), "Aria"), 7)
                .get("craftableCount").asInt());
    }

    @Test
    void accountScopedBankBatchReadIncludesTheTrustedGuardsWithoutMixingEqualSlots() throws Exception {
        mixedBank();
        JsonNode rows = JSON.valueToTree(new repo.BankRepository().loadBankSlots(admin, A));
        assertEquals(4, rows.size());
        assertEquals(List.of(0, 4, 9, 30), java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.get("slot").asInt()).toList());
        assertTrue(rows.get(0).has("binding") && rows.get(1).has("boundTo"), "batch rows must carry both nullable guards");
        assertEquals("Account", rows.get(0).get("binding").asText());
        assertEquals("Aria", rows.get(1).get("boundTo").asText());
        assertEquals("Bran", rows.get(2).get("boundTo").asText());
        assertTrue(rows.get(3).has("binding") && rows.get(3).get("binding").isNull());
        assertTrue(rows.get(3).has("boundTo") && rows.get(3).get("boundTo").isNull());
        assertEquals(999, new repo.BankRepository().loadBankSlots(admin, B).getFirst().count());
    }

    @Test
    void equalSlotsInAnotherAccountUseOnlyThatAccountsCurrentLayoutAndInventory() throws Exception {
        List<BankStack> observed = mixedBank();
        String settings = manualSettings(250, observed, Map.of(9, 250));
        assertEquals(80, row(calculate(false, settings, null), 7).get("craftableCount").asInt());
        mvc = MockMvcBuilders.standaloneSetup(new CraftingProfitApiController(new LocalCalculation(),
                        new web.access.AccountAccessHttp("")))
                .defaultRequest(AuthorizedRequests.forAccount(B.accountId()))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        JsonNode table = calculate(false, settings, null);
        JsonNode detail = calculate(true, settings, null);

        assertEquals(152, row(table, 7).get("craftableCount").asInt(), "(999 Bank - 250 + 777 Materials) / 10");
        assertEquals(row(table, 7), detail.get("row"));
        assertEquals(1050, new InventoryRepository().loadOwnedInventory(admin, A).get(200));
        assertEquals(1776, new InventoryRepository().loadOwnedInventory(admin, B).get(200));
    }

    private void tenUnitRecipe() {
        recipes = List.of(new Recipe(7, 100, 1, 0, "Artificer", List.of(new Ingredient(200, 10))));
    }

    private List<BankStack> mixedBank() throws Exception {
        tenUnitRecipe();
        List<BankStack> stacks = List.of(new BankStack(30, 200, 250, null, null),
                new BankStack(9, 200, 250, "Character", "Bran"),
                new BankStack(4, 200, 250, "Character", "Aria"), new BankStack(0, 200, 250, "Account", null));
        replaceBank(stacks);
        return stacks;
    }

    private void replaceBank(List<BankStack> stacks) throws Exception {
        execute("DELETE FROM account_bank WHERE account_id = '" + A.accountId() + "'");
        try (PreparedStatement insert = admin.prepareStatement("INSERT INTO account_bank"
                + " (account_id, slot, item_id, count, binding, bound_to) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (BankStack stack : stacks) {
                insert.setObject(1, A.accountId());
                insert.setInt(2, stack.slot());
                insert.setInt(3, stack.itemId());
                insert.setInt(4, stack.count());
                insert.setString(5, stack.binding());
                insert.setString(6, stack.boundTo());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String manualSettings(int target, List<BankStack> observed, Map<Integer, Integer> reserved) {
        var settings = JSON.createObjectNode().put("useOwnMats", true).put("allowBuying", false)
                .put("materialProtectionEnabled", true);
        settings.putArray("protectedItemIds");
        settings.putObject("protectedBankQuantities").put("200", target);
        var allocations = settings.putArray("protectedBankAllocations");
        observed.stream().sorted(java.util.Comparator.comparingInt(BankStack::slot)).forEach(stack ->
                allocations.addObject().put("slot", stack.slot()).put("itemId", stack.itemId())
                        .put("reservedUnits", reserved.getOrDefault(stack.slot(), 0)).put("observedCount", stack.count())
                        .put("binding", stack.binding()).put("boundTo", stack.boundTo()));
        return settings.toString();
    }

    private static JsonNode row(JsonNode table, int recipeId) {
        for (JsonNode row : table.get("rows")) if (row.get("recipeId").asInt() == recipeId) return row;
        throw new AssertionError("missing recipe " + recipeId);
    }

    private record BankStack(int slot, int itemId, int count, String binding, String boundTo) { }

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
        private LocalCalculation() { super(null, null, null, null, null, null, null, null); }

        @Override
        public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings,
                KeyedAdmission keyed, application.access.AccountContext readContext) throws Exception {
            try (ProfitInputSession session = ProfitInputSession.open(scope)) {
                return new ProfitOutcome(service(session).reload(choice, settings), null);
            }
        }

        @Override
        public ResolutionOutcome resolution(AccountScope scope, int recipeId, DiscChoice choice,
                                             CraftingSettings settings, String basedOnCalculationId,
                KeyedAdmission keyed, application.access.AccountContext readContext) throws Exception {
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
                return quotes;
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
