package web;

import application.CraftingProfitService;
import craft.Ingredient;
import craft.MysticForgeCatalog;
import craft.PriceQuote;
import craft.Recipe;
import craft.RecipeSource;
import org.junit.jupiter.api.Test;
import repo.ItemRepository;
import repo.tp.TpPriceRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MysticForgeApiControllerTest {

    /** The catalogue reads cached metadata and quotes in two batched calls and never touches a planner or account. */
    @Test
    void catalogueIsCheapMetadataWithReliableSubtypeQuotesAndSeparateReferenceRows() throws Exception {
        Recipe bow = new Recipe(-1, 500, 1, 0, "", List.of(new Ingredient(1, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        Recipe promo = Recipe.forgeWithExpectedYield(-2, 600, craft.ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(600, 1)));
        var catalog = new MysticForgeCatalog(List.of(bow, promo), 0, "test");
        AtomicInteger itemCalls = new AtomicInteger(), quoteCalls = new AtomicInteger();
        var items = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) {
                itemCalls.incrementAndGet();
                Map<Integer, ItemInfo> m = new HashMap<>();
                m.put(500, new ItemInfo(500, "Aurene's Wing", "https://i/x.png", 0, "Weapon", "LongBow"));
                m.put(600, new ItemInfo(600, "Gossamer Scrap", null, 0, "CraftingMaterial", null));
                return m;
            }
        };
        var tp = new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Set<Integer> ids) {
                quoteCalls.incrementAndGet();
                return Map.of(500, new PriceQuote(1000, 1500), 600, new PriceQuote(0, null));
            }
        };
        var controller = new MysticForgeApiController(null, null, () -> catalog, items, tp);

        var out = controller.recipes();

        assertEquals(1, itemCalls.get());
        assertEquals(1, quoteCalls.get());
        assertEquals(2, out.recipeCount());
        assertEquals(2, out.referenceCount());
        var wing = out.recipes().stream().filter(r -> r.recipeId() == -1).findFirst().orElseThrow();
        assertEquals("LongBow", wing.itemSubtype());
        assertEquals("WEAPONS", wing.category());
        assertEquals("FIXED", wing.outcome());
        assertEquals(1500, wing.sellUnitCopper());
        assertEquals("https://wiki.guildwars2.com/wiki/Aurene%27s_Wing", wing.wikiUrl());
        var scrap = out.recipes().stream().filter(r -> r.recipeId() == -2).findFirst().orElseThrow();
        assertEquals("EXPECTED", scrap.outcome());
        assertEquals("18.92", scrap.expectedMean());
        assertEquals("MATERIALS", scrap.category());
        assertNull(scrap.buyUnitCopper(), "a zero quote is unpriced, not free");
        assertNull(scrap.sellUnitCopper());
        var refs = out.recipes().stream().filter(r -> "REFERENCE_RANDOM".equals(r.outcome())).toList();
        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(r -> r.outputCount() == 0 && r.expectedMean() == null),
                "a random Clover row never claims a fixed or expected yield");
    }

    /** Cold catalogue: a Weapon with no cached metadata demands exactly its public id on the shared run, nothing else. */
    @Test
    void coldCatalogueAdmitsOnlyThePublicIncompleteOutputIdsInTheBackgroundAndRereadFindsTheSubtype() throws Exception {
        Recipe bow = new Recipe(-1, 500, 1, 0, "", List.of(new Ingredient(1, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        var catalog = new MysticForgeCatalog(List.of(bow), 0, "test");
        var complete = new java.util.concurrent.atomic.AtomicBoolean();
        var idCalls = new java.util.ArrayList<Set<Integer>>();
        var other = new java.util.ArrayList<String>();
        var admitted = new java.util.ArrayList<application.BackgroundTasks.Work>();
        var gateway = new sync.TradingPostPriceRefreshGateway() {
            @Override public void syncTpPricesForIds(Set<Integer> ids) { idCalls.add(new java.util.TreeSet<>(ids)); complete.set(true); }
            @Override public void syncTpPricesForProfit() { other.add("global"); }
            @Override public void syncTpPricesForProfit(model.AccountScope s) { other.add("scope"); }
        };
        var tasks = new application.BackgroundTasks() {
            @Override public String submit(String op, Work w) { admitted.add(w); return "t" + admitted.size(); }
            @Override public java.util.Optional<Status> find(String id) { return java.util.Optional.empty(); }
        };
        var coordinator = new application.TradingPostRefreshCoordinator(
                new application.TradingPostPriceRefreshService(gateway), tasks);
        var items = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) {
                Map<Integer, ItemInfo> m = new HashMap<>();
                if (complete.get()) for (int id : ids) {
                    m.put(id, id == 500 ? new ItemInfo(500, "Aurene's Wing", null, 0, "Weapon", "LongBow")
                            : new ItemInfo(id, "Item " + id, null, 0, "CraftingMaterial", null));
                }
                return m;
            }
            @Override public Set<Integer> incompleteMetadataIds(Set<Integer> ids) {
                return complete.get() ? Set.of() : new java.util.TreeSet<>(ids);
            }
        };
        var controller = new MysticForgeApiController(null, null, () -> catalog, items, new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Set<Integer> ids) { return Map.of(); }
            @Override public Set<Integer> staleOrMissingTradeable(Set<Integer> ids) { return Set.of(); }
        }, coordinator);

        var cold = controller.recipes();
        assertTrue(cold.completing());
        assertNull(cold.recipes().get(0).itemSubtype());
        assertEquals(1, admitted.size(), "one coalesced background task");
        assertEquals(List.of(), idCalls, "the request thread did no upstream work");
        controller.recipes();
        assertEquals(1, admitted.size(), "single-flight: a second read admits nothing new");

        admitted.get(0).run();
        assertEquals(1, idCalls.size());
        assertTrue(idCalls.get(0).stream().allMatch(id -> id > 0), "public positive output ids only");
        assertTrue(idCalls.get(0).contains(500));
        assertEquals(List.of(), other, "no Profit/global/account work");
        var warm = controller.recipes();
        assertEquals("LongBow", warm.recipes().get(0).itemSubtype());
        assertEquals(1, admitted.size());
    }

    private static MysticForgeApiController catalogueController(String subtype, boolean iconPresent, Set<Integer> staleQuoteIds,
                                                                 List<Set<Integer>> demanded, List<Integer> tasksAdmitted) {
        Recipe bow = new Recipe(-1, 500, 1, 0, "", List.of(new Ingredient(1, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        var gateway = new sync.TradingPostPriceRefreshGateway() {
            @Override public void syncTpPricesForIds(Set<Integer> ids) { demanded.add(new java.util.TreeSet<>(ids)); }
        };
        var tasks = new application.BackgroundTasks() {
            @Override public String submit(String op, Work w) { tasksAdmitted.add(1); return "t" + tasksAdmitted.size(); }
            @Override public java.util.Optional<Status> find(String id) { return java.util.Optional.empty(); }
        };
        var coordinator = new application.TradingPostRefreshCoordinator(
                new application.TradingPostPriceRefreshService(gateway), tasks);
        var items = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) {
                Map<Integer, ItemInfo> m = new HashMap<>();
                for (int id : ids) m.put(id, id == 500 ? new ItemInfo(500, "Aurene's Wing", null, 0, "Weapon", subtype)
                        : new ItemInfo(id, "Item " + id, null, 0, "CraftingMaterial", null));
                return m;
            }
            /** Stands in for the canonical IconSync policy: the output row lacks a subtype or an accepted icon. */
            @Override public Set<Integer> incompleteMetadataIds(Set<Integer> ids) {
                boolean bad = subtype == null || subtype.isBlank() || !iconPresent;
                return bad && ids.contains(500) ? Set.of(500) : Set.of();
            }
        };
        return new MysticForgeApiController(null, null, () -> new MysticForgeCatalog(List.of(bow), 0, "test"), items,
                new TpPriceRepository() {
                    @Override public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Set<Integer> ids) { return Map.of(); }
                    @Override public Set<Integer> staleOrMissingTradeable(Set<Integer> ids) { return staleQuoteIds; }
                }, coordinator);
    }

    /** The installed DB has names and Weapon type but NULL/blank subtype (V18): that alone triggers the repair. */
    @Test
    void knownNameAndTypeWithMissingOrBlankWeaponSubtypeStillDemandsCompletion() throws Exception {
        for (String subtype : new String[] {null, "  "}) {
            var tasks = new java.util.ArrayList<Integer>();
            var out = catalogueController(subtype, true, Set.of(), new java.util.ArrayList<>(), tasks).recipes();
            assertTrue(out.completing(), "subtype=" + subtype);
            assertEquals(1, tasks.size());
        }
    }

    /** Valid name/type/subtype but an absent/rejected icon still admits completion; a fully complete item admits nothing. */
    @Test
    void missingIconWithOtherwiseValidMetadataDemandsCompletionAndCompleteItemDoesNot() throws Exception {
        var tasks = new java.util.ArrayList<Integer>();
        assertTrue(catalogueController("LongBow", false, Set.of(), new java.util.ArrayList<>(), tasks).recipes().completing());
        assertEquals(1, tasks.size());
        var none = new java.util.ArrayList<Integer>();
        assertFalse(catalogueController("LongBow", true, Set.of(), new java.util.ArrayList<>(), none).recipes().completing());
        assertEquals(0, none.size(), "no redundant admission");
    }

    /** Complete metadata with a stale/missing eligible quote demands exactly those ids; fresh/nontradeable reads admit nothing. */
    @Test
    void completeMetadataWithStaleQuoteDemandsOnlyStaleEligibleIdsAndFreshReadsAdmitNothing() throws Exception {
        var demanded = new java.util.ArrayList<Set<Integer>>();
        var tasks = new java.util.ArrayList<Integer>();
        var stale = catalogueController("LongBow", true, Set.of(500), demanded, tasks);
        assertTrue(stale.recipes().completing());
        assertEquals(1, tasks.size(), "scoped demand admitted on the shared run");
        assertEquals(List.of(), demanded, "the request thread did no upstream work");

        var tasks2 = new java.util.ArrayList<Integer>();
        var fresh = catalogueController("LongBow", true, Set.of(), new java.util.ArrayList<>(), tasks2); // fresh or nontradeable
        assertFalse(fresh.recipes().completing());
        fresh.recipes();
        fresh.recipes();
        assertEquals(0, tasks2.size(), "repeated fresh/nontradeable reads admit no work");
    }

    /** Owner decision: Forge always counts all owned stock (no protection/reservation flag); only the six menu settings are added. */
    @Test
    void projectRequestCarriesOnlyRecipeTargetAndTheSixMenuSettings() {
        var names = java.util.Arrays.stream(web.dto.MysticForgeDtos.ProjectRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("recipeId", "target", "priceMode", "useOwn", "valueOwn", "daily", "promotions", "reduce"), names);
    }

    /** Validation happens before any account work: the (null) service is never reached for an invalid request. */
    @Test
    void invalidProjectRequestsAreRejectedBeforeAnyAccountWork() {
        var controller = new MysticForgeApiController(null, null, () -> new MysticForgeCatalog(List.of(), 0, "t"),
                new ItemRepository(), new TpPriceRepository());
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        for (var bad : List.of(new web.dto.MysticForgeDtos.ProjectRequest(null, 1L),
                new web.dto.MysticForgeDtos.ProjectRequest(5, 1L),          // ordinary recipe id, not a Forge id
                new web.dto.MysticForgeDtos.ProjectRequest(-1, 0L),
                new web.dto.MysticForgeDtos.ProjectRequest(-1, 9_999_999_999L))) {
            assertThrows(ApiValidationException.class, () -> controller.project(bad, http), String.valueOf(bad));
        }
        assertThrows(ApiValidationException.class, () -> controller.project(null, http));
    }

    /** Planner already stops on gold overflow; the mapping must not throw again but expose an unavailable line total. */
    @Test
    void overflowingShoppingLineIsATruthfulIncompleteProjectNotAnHttpError() {
        Recipe g = Recipe.forgeWithExpectedYield(-78, 19745, craft.ExpectedYield.of("18.92", 10, 40), List.of(
                new craft.Ingredient(19748, 250)));
        var planner = new craft.ForgeProjectPlanner(List.of(g));
        Map<Integer, PriceQuote> tp = Map.of(19748, new PriceQuote(1, 100_000_000));
        var project = planner.plan(g, 1_000_000_000L, true, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp);
        var data = new CraftingProfitService.ForgeProjectData(project, false, true, 1_000_000_000L, new HashMap<>(), tp, true);

        var dto = MysticForgeApiController.toProject(data, null);

        assertFalse(dto.complete());
        assertNotNull(dto.incompleteReason());
        var line = dto.shopping().stream().filter(l -> l.item().itemId() == 19748).findFirst().orElseThrow();
        assertEquals(250_000_000_000L, line.toAcquire());
        assertEquals(100_000_000L, line.unitCopper());
        assertNull(line.knownCopper(), "an overflowing total is unavailable, never zero or saturated");
    }

    @Test
    void notesMappingCarriesBackendFactsAndBothVariantsForThePopup() {
        Recipe root = new Recipe(-7, 900, 1, 0, "", List.of(), List.of(new craft.CurrencyRequirement(61, 210)),
                craft.RecipeSource.MYSTIC_FORGE);
        Recipe a = new Recipe(15120, 104934, 1, 0, "Artificer", List.of(new craft.Ingredient(5003, 4)));
        Recipe b = new Recipe(15131, 104934, 1, 0, "Artificer", List.of(new craft.Ingredient(5002, 3)));
        var planner = new craft.ForgeProjectPlanner(List.of(root, a, b));
        Map<Integer, PriceQuote> tp = Map.of(5003, new PriceQuote(null, 10), 5002, new PriceQuote(null, 100));
        var project = planner.plan(root, 1, true, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(61, 60L), tp);
        var data = new CraftingProfitService.ForgeProjectData(project, false, true, 1, new HashMap<>(), tp, true);

        var n = MysticForgeApiController.toProject(data, null).notes();

        assertEquals(210, n.needed());
        assertEquals(60L, n.walletUsed());
        assertEquals(150L, n.shortfall());
        assertEquals(1, n.containersCrafted());
        assertEquals(16L, n.estimateCopper());
        assertEquals(15120, n.basisRecipeId());
        assertEquals(2, n.variants().size());
        assertTrue(n.variants().get(0).basis());
    }

    /** A vendor currency total above Integer.MAX_VALUE stays exact and positive through domain and JSON. */
    @Test
    void vendorCurrencyTotalAboveIntRangeStaysExactThroughMapper() throws Exception {
        Recipe t = Recipe.forgeWithExpectedYield(-78, 19745, craft.ExpectedYield.of("1", 1, 1),
                List.of(new craft.Ingredient(20796, 1_000_000_000)));
        var planner = new craft.ForgeProjectPlanner(List.of(t,
                Recipe.vendor(-1_000_000_001, 20796, 10, List.of(new craft.CurrencyRequirement(23, 50)), 0)));
        var project = planner.planOutput(t, 1, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        var vendor = project.vendorPurchases().getFirst();
        assertEquals(100_000_000L, vendor.purchases());
        assertEquals(5_000_000_000L, vendor.currencies().getFirst().needed());
        assertEquals(5_000_000_000L, project.currencies().getFirst().needed());
        var dto = MysticForgeApiController.toProject(
                new CraftingProfitService.ForgeProjectData(project, false, true, 1, new HashMap<>(), Map.of(), true), null);
        var line = dto.shopping().stream().filter(s -> "VENDOR".equals(s.source())).findFirst().orElseThrow();
        assertEquals(5_000_000_000L, line.currencyCosts().getFirst().count());
        assertEquals(5_000_000_000L, dto.currencies().getFirst().needed());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(line.currencyCosts().getFirst());
        assertTrue(json.contains("\"count\":5000000000"), json);
    }

    /** Every project item and the catalogue go through the same-origin icon route; currencies get names; economics are backend-made. */
    @Test
    void projectMappingUsesSameOriginIconsHumanCurrenciesAndBackendEconomics() {
        String render = "https://render.guildwars2.com/file/0123456789ABCDEF0123456789ABCDEF01234567/66582.png";
        Recipe g = Recipe.forgeWithExpectedYield(-77, 19745, craft.ExpectedYield.of("18.92", 10, 40), List.of(
                new craft.Ingredient(19745, 1), new craft.Ingredient(19748, 250), new craft.Ingredient(20796, 5)));
        var planner = new craft.ForgeProjectPlanner(List.of(g,
                Recipe.vendor(-1_000_000_001, 20796, 10, List.of(new craft.CurrencyRequirement(23, 1)), 0)));
        Map<Integer, PriceQuote> tp = Map.of(19745, new PriceQuote(90, 100), 19748, new PriceQuote(1, 2));
        var project = planner.plan(g, 2, true, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp);
        Map<Integer, ItemRepository.ItemInfo> info = new HashMap<>();
        info.put(19745, new ItemRepository.ItemInfo(19745, "Gossamer Scrap", render, 0, "CraftingMaterial", null));
        info.put(19748, new ItemRepository.ItemInfo(19748, "Bolt of Gossamer", render, 0, "CraftingMaterial", null));
        var data = new CraftingProfitService.ForgeProjectData(project, false, true, 2, info, tp, true);

        var dto = MysticForgeApiController.toProject(data, null);

        assertTrue(dto.output().iconUrl().startsWith("/api/items/19745/icon/"), dto.output().iconUrl());
        assertFalse(dto.output().iconUrl().contains("render.guildwars2.com"));
        dto.tree().children().forEach(c -> {
            if (c.item().iconUrl() != null) assertTrue(c.item().iconUrl().startsWith("/api/items/"), c.item().iconUrl());
        });
        var shards = dto.currencies().getFirst();
        assertEquals("Spirit Shards", shards.name());
        assertEquals(1L, shards.needed());
        assertEquals(0L, shards.owned(), "a known empty wallet map means exactly zero");
        assertEquals(1, dto.seed().externalSeedUnits());
        assertEquals("36.84", dto.seed().netExpectedOutput());
        assertEquals(3315L, dto.economics().expectedGrossProceedsCopper(), "proceeds use the bid 90, not the ask 100");
        assertEquals(497L, dto.economics().feeCopper());
        assertTrue(dto.economics().conditional());
    }

    /** Selected value setting reconciles the displayed margin: No excludes the owned 100c input, Yes subtracts it once. */
    @Test
    void equipmentMarginReconcilesWithSelectedOwnValueSetting() {
        Recipe t = new Recipe(-2, 900, 1, 0, "", List.of(new Ingredient(5001, 1), new Ingredient(5002, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(1, 100), 5002, new PriceQuote(1, 50), 900, new PriceQuote(1000, 2000));
        for (boolean value : new boolean[] {false, true}) {
            var settings = new craft.ForgeSettings(craft.ForgeSettings.PriceMode.INSTANT_BUY, true, value,
                    craft.ForgeSettings.DailyMode.CRAFT, true, craft.ForgeSettings.ReduceMode.YES);
            var project = new craft.ForgeProjectPlanner(List.of(t)).plan(t, 1, false,
                    new craft.ForgeProjectPlanner.Inventory(Map.of(5001, 1), Map.of(), Map.of()),
                    craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp, settings);
            var dto = MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                    project, false, false, 1, new HashMap<>(), tp, true), null);
            assertEquals(850L, dto.economics().expectedNetProceedsCopper());
            assertEquals(800L, dto.economics().marginOnShoppingCopper(), "cash-only margin never subtracts the owned input");
            assertEquals(value ? 799L : 800L, dto.economics().marginCopper(), "owned stock valued once at the instant-sell bid 1 (not the ask 100) only when valued");
        }
    }

    @Test
    void lateEconomicsOverflowPublishesNoGuaranteedOutput() {
        Recipe t = new Recipe(-1, 900, 1, 0, "", List.of(new Ingredient(5001, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        var project = new craft.ForgeProjectPlanner(List.of(t)).planOutput(t, 999_999_999L,
                craft.ForgeProjectPlanner.Inventory.shared(Map.of()), craft.ForgeProjectPlanner.Context.NONE, Map.of(),
                Map.of(5001, new PriceQuote(null, 10_000_001)));
        var data = new CraftingProfitService.ForgeProjectData(project, true, true, 999_999_999L, new HashMap<>(), Map.of(), true);
        var dto = MysticForgeApiController.toProject(data, null);
        assertFalse(dto.complete());
        assertNotNull(dto.incompleteReason());
        assertNull(dto.guaranteedOutput());
    }

    /** Ask-only overflow: the cost/proceeds side is fine, only unitBuy * target leaves the exact range. */
    @Test
    void comparisonBuyTotalOverflowStopsTheProjectWithoutAGuaranteedOutput() {
        Recipe t = new Recipe(-2, 900, 1, 0, "", List.of(new Ingredient(5001, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(1, 1), 900, new PriceQuote(1, 10_000_001));
        var project = new craft.ForgeProjectPlanner(List.of(t)).planOutput(t, 1_000_000_000L,
                craft.ForgeProjectPlanner.Inventory.shared(Map.of()), craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp);
        assertTrue(project.complete(), "the planner alone cannot see the controller-side comparison range");
        var dto = MysticForgeApiController.toProject(
                new CraftingProfitService.ForgeProjectData(project, false, false, 1_000_000_000L, new HashMap<>(), tp, true), null);
        assertFalse(dto.complete());
        assertEquals("quantity exceeds the supported range", dto.incompleteReason());
        assertNull(dto.guaranteedOutput());
        assertEquals(10_000_001L, dto.comparison().unitBuyCopper());
        assertNull(dto.comparison().outputBuyTotalCopper(), "no saturated or zero total");
        assertNull(dto.comparison().savingsCopper());
        assertTrue(dto.goldCostPartial());
        assertNull(dto.economics().marginCopper(), "a range stop leaves no authoritative full margin");
        assertNull(dto.economics().marginOnShoppingCopper());
    }

    /** Both reference-only Clover variants at several batch counts: inputs scale, the root claims no output or craft. */
    @Test
    void referenceOnlyCloverRootNeverClaimsGuaranteedOutputNeededOrToCraft() {
        for (var ref : craft.ForgeReferenceRecipes.all()) {
            for (long batches : new long[] {1, 3, 10}) {
                Recipe root = ref.asPlanningTarget();
                var planner = new craft.ForgeProjectPlanner(List.of());
                var project = planner.plan(root, batches, true, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                        craft.ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
                var data = new CraftingProfitService.ForgeProjectData(project, true, true, batches, new HashMap<>(), Map.of(), true);

                var dto = MysticForgeApiController.toProject(data, null);
                String label = ref.recipeId() + " x" + batches;

                assertNull(dto.guaranteedOutput(), label);
                assertEquals("REFERENCE_RANDOM", dto.outcome(), label);
                assertEquals("BATCHES", dto.targetKind(), label);
                assertNull(dto.economics(), label);
                assertNull(dto.seed(), label);
                var rootNode = dto.tree();
                assertEquals(0L, rootNode.needed(), label);
                assertEquals(0L, rootNode.toCraft(), label);
                assertEquals(0L, rootNode.fromOwned(), label);
                assertEquals(0L, rootNode.toAcquire(), label);
                assertEquals(ref.ingredients().size(), rootNode.children().size(), label);
                for (int k = 0; k < ref.ingredients().size(); k++) {
                    assertEquals(ref.ingredients().get(k).count * batches, rootNode.children().get(k).needed(), label);
                }
            }
        }
    }

    @Test
    void expectedBatchRootPublishesNetGuaranteedOutputInJson() throws Exception {
        Recipe g = Recipe.forgeWithExpectedYield(-77, 19745, craft.ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(19748, 250), new Ingredient(19745, 1)));
        var project = new craft.ForgeProjectPlanner(List.of(g)).plan(g, 2, true,
                craft.ForgeProjectPlanner.Inventory.shared(Map.of(19748, 1000L, 19745, 1L)),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(19745, new PriceQuote(100, 110), 19748, new PriceQuote(1, 2)));
        var data = new CraftingProfitService.ForgeProjectData(project, false, true, 2, new HashMap<>(), Map.of(), true);
        var dto = MysticForgeApiController.toProject(data, null);
        assertEquals("EXPECTED", dto.outcome());
        assertEquals("BATCHES", dto.targetKind());
        assertEquals(2L, dto.batches());
        assertTrue(project.complete(), project.incompleteReason());
        assertEquals(19L, dto.guaranteedOutput());
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto).contains("\"guaranteedOutput\":19"));
    }

    // ---- full-path equipment economics: wallet Notes, conditionality, combined range (issue 378) ----

    private static final craft.ForgeSettings VALUE_OWN = new craft.ForgeSettings(craft.ForgeSettings.PriceMode.INSTANT_BUY, true, true,
            craft.ForgeSettings.DailyMode.CRAFT, true, craft.ForgeSettings.ReduceMode.YES);

    private static web.dto.MysticForgeDtos.Project notesProject(long wallet, Map<Integer, PriceQuote> tp) {
        Recipe root = new Recipe(-7, 900, 1, 0, "", List.of(new Ingredient(5001, 1)),
                List.of(new craft.CurrencyRequirement(61, 30)), RecipeSource.MYSTIC_FORGE);
        Recipe a = new Recipe(15120, 104934, 1, 0, "Artificer", List.of(new Ingredient(5003, 4)));
        Recipe b = new Recipe(15131, 104934, 1, 0, "Artificer", List.of(new Ingredient(5002, 4)));
        var planner = new craft.ForgeProjectPlanner(List.of(root, a, b));
        var project = planner.plan(root, 1, false, craft.ForgeProjectPlanner.Inventory.shared(Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(61, wallet), tp, VALUE_OWN);
        return MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                project, false, false, 1, new HashMap<>(), tp, true), null);
    }

    @Test
    void walletNotesAreSubtractedOnceAtTheEstimateAndGeneratedNotesAreNotValuedAgain() {
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(1, 100), 5002, new PriceQuote(1, 100), 5003, new PriceQuote(1, 10),
                900, new PriceQuote(1000, 2000));
        var wallet = notesProject(30, tp); // 30 wallet Notes valued at 40c / 150 Notes = 8c
        assertEquals(8L, wallet.notes().estimateCopper());
        assertEquals(850L, wallet.economics().expectedNetProceedsCopper());
        assertEquals(750L, wallet.economics().marginOnShoppingCopper(), "bought cash only");
        assertEquals(742L, wallet.economics().marginCopper(), "850 - 100 bought - 8 valued wallet Notes");
        assertEquals(2000L - 108L, wallet.comparison().savingsCopper());
        assertFalse(wallet.economics().conditional(), "Notes valued and nothing else unvalued");
        var generated = notesProject(0, tp); // one 150-Note container crafted: 4 x 10c charged through its inputs only
        assertEquals(1, generated.notes().containersCrafted());
        assertEquals(710L, generated.economics().marginCopper(), "850 - (100 + 40 container inputs), no second Note value");
        assertEquals(2000L - 140L, generated.comparison().savingsCopper());
    }

    @Test
    void unavailableWalletNoteEstimateMakesTheFullMarginAndSavingsUnavailableNeverFree() {
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(1, 100), 900, new PriceQuote(1000, 2000));
        var dto = notesProject(30, tp);
        assertNull(dto.notes().estimateCopper());
        assertEquals(750L, dto.economics().marginOnShoppingCopper());
        assertNull(dto.economics().marginCopper());
        assertNull(dto.comparison().savingsCopper());
        assertTrue(dto.economics().conditional());
        assertTrue(dto.comparison().conditional());
    }

    @Test
    void fullyPricedExpandedGiftPathIsNotConditionalButUnvaluedCurrencyIs() {
        Recipe gift = new Recipe(-50, 6000, 1, 0, "", List.of(new Ingredient(5001, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Recipe t = new Recipe(-51, 900, 1, 0, "", List.of(new Ingredient(6000, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(1, 100), 900, new PriceQuote(1000, 2000));
        var project = new craft.ForgeProjectPlanner(List.of(gift, t)).plan(t, 1, false,
                craft.ForgeProjectPlanner.Inventory.shared(Map.of()), craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp, VALUE_OWN);
        var dto = MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                project, false, false, 1, new HashMap<>(), tp, true), null);
        assertEquals(750L, dto.economics().marginCopper());
        assertFalse(dto.economics().conditional(), "expanded priced gift path: no structural conditional from the untradeable gift");
        Recipe gift2 = new Recipe(-52, 6000, 1, 0, "", List.of(new Ingredient(5001, 1)),
                List.of(new craft.CurrencyRequirement(23, 5)), RecipeSource.MYSTIC_FORGE);
        var project2 = new craft.ForgeProjectPlanner(List.of(gift2, t)).plan(t, 1, false,
                craft.ForgeProjectPlanner.Inventory.shared(Map.of()), craft.ForgeProjectPlanner.Context.NONE, Map.of(23, 0L), tp, VALUE_OWN);
        var dto2 = MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                project2, false, false, 1, new HashMap<>(), tp, true), null);
        assertTrue(dto2.economics().conditional(), "an unvalued Spirit Shard requirement stays conditional");
    }

    /** Each subtotal is within 2^53-1 but their combination is not; bid-only output, no ask/comparison needed. */
    @Test
    void combinedDeductionOverflowStopsThroughTheControllerPathWithSafeSubfactsKept() {
        Recipe t = new Recipe(-60, 900, 1, 0, "", List.of(new Ingredient(5001, 1), new Ingredient(5002, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Map<Integer, PriceQuote> tp = Map.of(5001, new PriceQuote(null, 9_000_000), 5002, new PriceQuote(9_000_000, null),
                900, new PriceQuote(10, null));
        var project = new craft.ForgeProjectPlanner(List.of(t)).plan(t, 1_000_000_000L, false,
                new craft.ForgeProjectPlanner.Inventory(Map.of(5002, 1_000_000_000), Map.of(), Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp, VALUE_OWN);
        assertTrue(project.complete(), "the planner alone sees only safe subtotals");
        assertEquals(9_000_000_000_000_000L, project.knownCopper());
        assertEquals(9_000_000_000_000_000L, project.ownValue().knownCopper());
        var dto = MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                project, false, false, 1_000_000_000L, new HashMap<>(), tp, true), null);
        assertFalse(dto.complete());
        assertEquals("quantity exceeds the supported range", dto.incompleteReason());
        assertNull(dto.guaranteedOutput());
        assertTrue(dto.goldCostPartial());
        assertNull(dto.economics().marginCopper());
        assertEquals(8_500_000_000L, dto.economics().expectedNetProceedsCopper(), "safe unit/subtotal facts stay");
        assertEquals(9_000_000_000_000_000L, dto.knownGoldCopper());
    }

    @Test
    void consumedOwnValueOverflowThroughTheControllerPathIsAnIncompleteProject() {
        Recipe t = new Recipe(-61, 900, 1, 0, "", List.of(new Ingredient(5002, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        Map<Integer, PriceQuote> tp = Map.of(5002, new PriceQuote(9_000_000, null), 900, new PriceQuote(10, 20));
        var project = new craft.ForgeProjectPlanner(List.of(t)).plan(t, 2_000_000_000L, false,
                new craft.ForgeProjectPlanner.Inventory(Map.of(5002, 2_000_000_000), Map.of(), Map.of()),
                craft.ForgeProjectPlanner.Context.NONE, Map.of(), tp, VALUE_OWN);
        var dto = MysticForgeApiController.toProject(new CraftingProfitService.ForgeProjectData(
                project, false, false, 2_000_000_000L, new HashMap<>(), tp, true), null);
        assertFalse(dto.complete());
        assertNull(dto.guaranteedOutput());
        assertNull(dto.economics().marginCopper());
        assertNull(dto.comparison().savingsCopper());
    }
}
