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
            @Override public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> ids) {
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
        };
        var controller = new MysticForgeApiController(null, null, () -> catalog, items, new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> ids) { return Map.of(); }
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
}
