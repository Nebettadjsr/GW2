package application;

import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-002) for
 * {@link CraftingDiscoveryService}: fake/in-memory adapters replace every repository and the
 * crafting-graph cache, so these run without PostgreSQL, while the real {@link CraftingPlanner}
 * (wrapped to record its inputs) still performs the actual domain calculation - this suite proves
 * orchestration/wiring, not domain math (that is covered by {@code craft.*} tests).
 */
class CraftingDiscoveryServiceTest {

    private static final Recipe RECIPE = new Recipe(
            1, 100, 1, 0, "Chef", List.of(new Ingredient(200, 2)));
    private static final Recipe OTHER_RECIPE = new Recipe(
            2, 999, 1, 0, "Chef", List.of());

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying) {
        return new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);
    }

    private static CraftingDiscoveryService serviceWith(FakeRecipeRepository recipeRepo,
                                                         FakeInventoryRepository invRepo,
                                                         FakeTpPriceRepository tpRepo,
                                                         FakeItemRepository itemRepo,
                                                         List<Recipe> graphRecipes,
                                                         RecordingCraftingPlanner planner) {
        return new CraftingDiscoveryService(recipeRepo, invRepo, tpRepo, itemRepo,
                new FakeCraftingGraphCache(graphRecipes), planner);
    }

    @Test
    void missingRecipesAreFilteredToTheGraphAndTheCharacterLevelCeiling() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1, 2);
        var planner = new RecordingCraftingPlanner();
        // Recipe 2 requires a higher rating than the chosen entry's maxLevel allows.
        Recipe tooHigh = new Recipe(2, 999, 1, 500, "Chef", List.of());
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), List.of(RECIPE, tooHigh), planner);

        CraftingDiscoveryService.DiscoveryData data =
                service.reload(DiscChoice.charDiscipline("Chef", 100, "Aria"), settings(false, false), "Aria");

        assertEquals("Aria", recipeRepo.capturedCharName);
        assertEquals("Chef", recipeRepo.capturedDiscipline);
        assertEquals(List.of(RECIPE), data.visibleRecipes(), "Recipe 2 exceeds the char entry's maxLevel and must be excluded");
        assertEquals(1, planner.capturedAllowedRecipeIds.size());
        assertTrue(planner.capturedAllowedRecipeIds.contains(1));
    }

    @Test
    void noMissingRecipesShortCircuitsBeforeLoadingInventoryPriceOrItemData() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of();
        var invRepo = new FakeInventoryRepository();
        var tpRepo = new FakeTpPriceRepository();
        var itemRepo = new FakeItemRepository();
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, tpRepo, itemRepo, List.of(RECIPE), planner);

        CraftingDiscoveryService.DiscoveryData data =
                service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        assertTrue(data.visibleRecipes().isEmpty());
        assertFalse(invRepo.calledForCharacter, "no missing recipes must skip the inventory load entirely");
        assertFalse(tpRepo.called, "no missing recipes must skip the TP price load entirely");
        assertFalse(itemRepo.called, "no missing recipes must skip the item load entirely");
        assertNull(planner.capturedAllowedRecipeIds, "no missing recipes must skip planner invocation entirely");
    }

    @Test
    void selectedCharacterBindingSplitIsHandedToPlanner() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var invRepo = new FakeInventoryRepository();
        invRepo.perCharacter = Map.of(200, new InventoryRepository.OwnedQuantity(3, 5));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, new FakeTpPriceRepository(),
                new FakeItemRepository(), List.of(RECIPE), planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        assertTrue(invRepo.calledForCharacter);
        assertEquals("Aria", invRepo.capturedCharacterName);
        assertEquals(Map.of(200, 3), planner.capturedSellable);
        assertEquals(Map.of(200, 5), planner.capturedBound);
    }

    @Test
    void noSelectedCharacterFallsBackToUnfilteredOwnedInventory() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var invRepo = new FakeInventoryRepository();
        invRepo.unfiltered = Map.of(200, 9);
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, new FakeTpPriceRepository(),
                new FakeItemRepository(), List.of(RECIPE), planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), null);

        assertTrue(invRepo.calledUnfiltered);
        assertFalse(invRepo.calledForCharacter);
        assertEquals(Map.of(200, 9), planner.capturedSellable);
        assertTrue(planner.capturedBound.isEmpty());
    }

    @Test
    void inventoryIsNotLoadedWhenUseOwnMatsIsFalse() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var invRepo = new FakeInventoryRepository();
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, new FakeTpPriceRepository(),
                new FakeItemRepository(), List.of(RECIPE), planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(false, true), "Aria");

        assertFalse(invRepo.calledForCharacter);
        assertFalse(invRepo.calledUnfiltered);
        assertTrue(planner.capturedSellable.isEmpty());
        assertTrue(planner.capturedBound.isEmpty());
    }

    @Test
    void blockedResultForUnpricedRequiredPurchaseIsReturnedNotDropped() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(100, new PriceQuote(100, 110)); // no quote for ingredient item 200
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), tpRepo,
                new FakeItemRepository(), List.of(RECIPE), planner);

        CraftingDiscoveryService.DiscoveryData data =
                service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        CraftResult result = data.resultsByRecipeId().get(1);
        assertNotNull(result);
        assertEquals(craft.BlockedReason.PRICE_UNAVAILABLE, result.blockedReason);
    }

    @Test
    void getResultByRecipeIdBuildsTreeOnceAndReloadDropsStaleEntries() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(100, new PriceQuote(100, 110), 200, new PriceQuote(10, 10));
        var planner = new RecordingCraftingPlanner();
        var graphCache = new FakeCraftingGraphCache(List.of(RECIPE));
        var service = new CraftingDiscoveryService(recipeRepo, new FakeInventoryRepository(), tpRepo,
                new FakeItemRepository(), graphCache, planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        CraftResult first = service.getResultByRecipeId(1);
        assertNotNull(first);
        assertNotNull(first.tree);

        CraftResult second = service.getResultByRecipeId(1);
        assertSame(first.tree, second.tree, "tree must be built once and cached, not rebuilt per call");

        // A later reload() whose crafting graph no longer contains recipe 1 must not leak the
        // previous reload's cached result (mirrors STORY-APP-001 acceptance criterion 3).
        recipeRepo.missingIds = List.of(2);
        graphCache.recipes = List.of(OTHER_RECIPE);
        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        assertNull(service.getResultByRecipeId(1));
    }

    @Test
    void itemNameAndSellUnitReflectTheMostRecentSuccessfulReload() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.missingIds = List.of(1);
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(200, new PriceQuote(50, 60));
        var itemRepo = new FakeItemRepository();
        itemRepo.canned = Map.of(200, new ItemRepository.ItemInfo(200, "Ore", null));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), tpRepo, itemRepo,
                List.of(RECIPE), planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        assertEquals("Ore", service.itemName(200));
        assertEquals(60, service.itemSellUnit(200, true));
        assertEquals(50, service.itemSellUnit(200, false));

        // A subsequent reload with no missing recipes must not clear the previous lookup data.
        recipeRepo.missingIds = List.of();
        service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, true), "Aria");

        assertEquals("Ore", service.itemName(200), "stale lookup data must survive a short-circuited reload");
        assertEquals(60, service.itemSellUnit(200, true));
    }

    @Test
    void repositoryFailurePropagatesFromReload() {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.failure = new SQLException("simulated repository failure");
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), List.of(RECIPE), planner);

        assertThrows(SQLException.class, () ->
                service.reload(DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(false, false), "Aria"));
    }

    // ---------- Fake/in-memory adapters (TARGET_ARCHITECTURE.md §25) ----------

    /** {@code recipes} is mutable so a test can change what the "cache file" contains between two reload() calls. */
    private static class FakeCraftingGraphCache extends CraftingGraphCache {
        List<Recipe> recipes;

        FakeCraftingGraphCache(List<Recipe> recipes) {
            super(null);
            this.recipes = recipes;
        }

        @Override
        public CraftingGraph load() {
            return new CraftingGraph(recipes);
        }
    }

    private static class FakeRecipeRepository extends RecipeRepository {
        List<Integer> missingIds = List.of();
        SQLException failure;
        String capturedCharName;
        String capturedDiscipline;

        @Override
        public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(String charName, String discipline) throws SQLException {
            if (failure != null) throw failure;
            capturedCharName = charName;
            capturedDiscipline = discipline;
            return missingIds;
        }
    }

    private static class FakeInventoryRepository extends InventoryRepository {
        Map<Integer, Integer> unfiltered = Map.of();
        Map<Integer, OwnedQuantity> perCharacter = Map.of();
        boolean calledUnfiltered = false;
        boolean calledForCharacter = false;
        String capturedCharacterName;

        @Override
        public Map<Integer, Integer> loadOwnedInventory() {
            calledUnfiltered = true;
            return unfiltered;
        }

        @Override
        public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(String selectedCharacterName) {
            calledForCharacter = true;
            capturedCharacterName = selectedCharacterName;
            return perCharacter;
        }
    }

    private static class FakeTpPriceRepository extends TpPriceRepository {
        Map<Integer, PriceQuote> canned = Map.of();
        boolean called = false;

        @Override
        public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) {
            called = true;
            return canned;
        }
    }

    private static class FakeItemRepository extends ItemRepository {
        Map<Integer, ItemInfo> canned = Map.of();
        boolean called = false;

        @Override
        public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) {
            called = true;
            return canned;
        }
    }

    private static class RecordingCraftingPlanner extends CraftingPlanner {
        List<Recipe> capturedRecipes;
        Map<Integer, Integer> capturedSellable;
        Map<Integer, Integer> capturedBound;
        Map<Integer, PriceQuote> capturedTp;
        CraftingSettings capturedSettings;
        Set<Integer> capturedAllowedRecipeIds;

        @Override
        public Map<Integer, CraftResult> evaluateAll(List<Recipe> recipes,
                                                      Map<Integer, Integer> sellableInventory,
                                                      Map<Integer, Integer> boundInventory,
                                                      Map<Integer, PriceQuote> tp,
                                                      CraftingSettings settings,
                                                      Set<Integer> allowedRecipeIds) {
            this.capturedRecipes = recipes;
            this.capturedSellable = sellableInventory;
            this.capturedBound = boundInventory;
            this.capturedTp = tp;
            this.capturedSettings = settings;
            this.capturedAllowedRecipeIds = allowedRecipeIds;
            return super.evaluateAll(recipes, sellableInventory, boundInventory, tp, settings, allowedRecipeIds);
        }
    }
}
