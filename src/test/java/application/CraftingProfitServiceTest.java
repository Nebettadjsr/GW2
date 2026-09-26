package application;

import craft.CharacterCraftingProfile;
import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.MaterialTradeability;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-001) for
 * {@link CraftingProfitService}: fake/in-memory adapters replace every repository and the
 * crafting-graph cache, so these run without PostgreSQL, while the real {@link CraftingPlanner}
 * (wrapped to record its inputs) still performs the actual domain calculation - this suite proves
 * orchestration/wiring, not domain math (that is covered by {@code craft.*} tests).
 */
class CraftingProfitServiceTest {

    private static final Recipe RECIPE = new Recipe(
            1, 100, 1, 0, "Artificer", List.of(new Ingredient(200, 2)));

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying) {
        return new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);
    }

    private static CraftingProfitService serviceWith(FakeRecipeRepository recipeRepo,
                                                      FakeInventoryRepository invRepo,
                                                      FakeTpPriceRepository tpRepo,
                                                      FakeItemRepository itemRepo,
                                                      FakeCharacterRepository charRepo,
                                                      List<Recipe> graphRecipes,
                                                      RecordingCraftingPlanner planner) {
        return new CraftingProfitService(recipeRepo, invRepo, tpRepo, itemRepo, charRepo,
                new FakeCraftingGraphCache(graphRecipes), planner);
    }

    @Test
    void allScopeQueriesAllDisciplineAndKeepsFullMultiDisciplineRoster() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(
                new CharacterRepository.DiscRow("Aria", "Chef", 80, true),
                new CharacterRepository.DiscRow("Bran", "Chef", 40, true),
                new CharacterRepository.DiscRow("Bran", "Weaponsmith", 30, true));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), charRepo, List.of(RECIPE), planner);

        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), settings(false, false));

        assertEquals(List.of("All"), recipeRepo.disciplineQueries);
        assertTrue(recipeRepo.characterQueries.isEmpty());
        assertSame(recipeRepo.visible, data.visibleRecipes());

        assertEquals(2, planner.capturedRoster.size());
        var byName = new HashMap<String, CharacterCraftingProfile>();
        for (var p : planner.capturedRoster) byName.put(p.name(), p);
        assertEquals(Map.of("Chef", 80), byName.get("Aria").ratingByDiscipline());
        assertEquals(Map.of("Chef", 40, "Weaponsmith", 30), byName.get("Bran").ratingByDiscipline());
    }

    @Test
    void charDisciplineScopeRoutesToCharacterQueryAndFiltersRosterToThatCharacterAndDiscipline() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(
                new CharacterRepository.DiscRow("Aria", "Chef", 80, true),
                new CharacterRepository.DiscRow("Bran", "Chef", 40, true));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), charRepo, List.of(RECIPE), planner);

        service.reload(DiscChoice.charDiscipline("Chef", 400, "Bran"), settings(false, false));

        assertEquals(1, recipeRepo.characterQueries.size());
        assertEquals("Bran", recipeRepo.characterQueries.get(0)[0]);
        assertEquals("Chef", recipeRepo.characterQueries.get(0)[1]);
        assertTrue(recipeRepo.disciplineQueries.isEmpty());

        assertEquals(1, planner.capturedRoster.size());
        assertEquals("Bran", planner.capturedRoster.get(0).name());
        assertEquals(Map.of("Chef", 40), planner.capturedRoster.get(0).ratingByDiscipline());
    }

    @Test
    void disciplineOnlyScopeFiltersRosterToCharactersHoldingThatDiscipline() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(
                new CharacterRepository.DiscRow("Aria", "Chef", 80, true),
                new CharacterRepository.DiscRow("Bran", "Weaponsmith", 30, true));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), charRepo, List.of(RECIPE), planner);

        service.reload(DiscChoice.disciplineOnly("Weaponsmith"), settings(false, false));

        assertEquals(List.of("Weaponsmith"), recipeRepo.disciplineQueries);
        assertEquals(1, planner.capturedRoster.size());
        assertEquals("Bran", planner.capturedRoster.get(0).name());
        assertEquals(Map.of("Weaponsmith", 30), planner.capturedRoster.get(0).ratingByDiscipline());
    }

    @Test
    void ownMatsInventoryAndTpQuotesAreHandedToPlannerAndReturnedToCaller() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(new CharacterRepository.DiscRow("Novice", "Artificer", 50, true));
        var invRepo = new FakeInventoryRepository();
        invRepo.canned = new InventoryRepository.CoordinatedInventory(
                Map.of(200, 5), Map.of(300, 2), Map.of("Novice", Map.of(400, 1)));
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(100, new PriceQuote(100, 110));
        var itemRepo = new FakeItemRepository();
        itemRepo.canned = Map.of(100, new ItemRepository.ItemInfo(100, "Widget", null, null));
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, tpRepo, itemRepo, charRepo, List.of(RECIPE), planner);

        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), settings(true, true));

        assertTrue(invRepo.called);
        assertEquals(Set.of("Novice"), invRepo.capturedNames);
        assertEquals(Map.of(200, 5), planner.capturedSellable);
        assertEquals(Map.of(300, 2), planner.capturedAccountBound);
        assertEquals(Map.of("Novice", Map.of(400, 1)), planner.capturedCharacterBound);
        assertSame(tpRepo.canned, data.tp());
        assertSame(itemRepo.canned, data.items());
    }

    @Test
    void inventoryIsNotLoadedWhenUseOwnMatsIsFalse() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(new CharacterRepository.DiscRow("Novice", "Artificer", 50, true));
        var invRepo = new FakeInventoryRepository();
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, invRepo, new FakeTpPriceRepository(),
                new FakeItemRepository(), charRepo, List.of(RECIPE), planner);

        service.reload(DiscChoice.all(), settings(false, true));

        assertFalse(invRepo.called);
        assertTrue(planner.capturedSellable.isEmpty());
        assertTrue(planner.capturedAccountBound.isEmpty());
        assertTrue(planner.capturedCharacterBound.isEmpty());
    }

    @Test
    void blockedResultForUnpricedRequiredPurchaseIsReturnedNotDropped() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(new CharacterRepository.DiscRow("Novice", "Artificer", 50, true));
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(100, new PriceQuote(100, 110)); // no quote for ingredient item 200
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), tpRepo,
                new FakeItemRepository(), charRepo, List.of(RECIPE), planner);

        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), settings(true, true));

        CraftResult result = data.resultsByRecipeId().get(1);
        assertNotNull(result);
        assertEquals(craft.BlockedReason.PRICE_UNAVAILABLE, result.blockedReason);
    }

    @Test
    void getResultByRecipeIdBuildsTreeOnceAndReloadDropsStaleEntries() throws SQLException {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.visible = List.of(RECIPE);
        var charRepo = new FakeCharacterRepository();
        charRepo.canned = List.of(new CharacterRepository.DiscRow("Novice", "Artificer", 50, true));
        var tpRepo = new FakeTpPriceRepository();
        tpRepo.canned = Map.of(100, new PriceQuote(100, 110), 200, new PriceQuote(10, 10));
        var planner = new RecordingCraftingPlanner();
        var graphCache = new FakeCraftingGraphCache(List.of(RECIPE));
        var service = new CraftingProfitService(recipeRepo, new FakeInventoryRepository(), tpRepo,
                new FakeItemRepository(), charRepo, graphCache, planner);

        service.reload(DiscChoice.all(), settings(true, true));

        CraftResult first = service.getResultByRecipeId(1);
        assertNotNull(first);
        assertNotNull(first.tree);

        CraftResult second = service.getResultByRecipeId(1);
        assertSame(first.tree, second.tree, "tree must be built once and cached, not rebuilt per call");

        // A later reload() whose crafting graph no longer contains recipe 1 must not leak the
        // previous reload's cached result (STORY-APP-001 acceptance criterion 3).
        Recipe other = new Recipe(2, 999, 1, 0, "Chef", List.of());
        recipeRepo.visible = List.of(other);
        graphCache.recipes = List.of(other);
        service.reload(DiscChoice.all(), settings(true, true));

        assertNull(service.getResultByRecipeId(1));
    }

    @Test
    void repositoryFailurePropagatesFromReload() {
        var recipeRepo = new FakeRecipeRepository();
        recipeRepo.failure = new SQLException("simulated repository failure");
        var planner = new RecordingCraftingPlanner();
        var service = serviceWith(recipeRepo, new FakeInventoryRepository(), new FakeTpPriceRepository(),
                new FakeItemRepository(), new FakeCharacterRepository(), List.of(RECIPE), planner);

        assertThrows(SQLException.class, () -> service.reload(DiscChoice.all(), settings(false, false)));
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
        List<Recipe> visible = List.of();
        SQLException failure;
        final List<String> disciplineQueries = new ArrayList<>();
        final List<String[]> characterQueries = new ArrayList<>();

        @Override
        public List<Recipe> loadRecipes(String discipline) throws SQLException {
            if (failure != null) throw failure;
            disciplineQueries.add(discipline);
            return visible;
        }

        @Override
        public List<Recipe> loadRecipesForCharacter(String charName, String discipline) throws SQLException {
            if (failure != null) throw failure;
            characterQueries.add(new String[]{charName, discipline});
            return visible;
        }
    }

    private static class FakeInventoryRepository extends InventoryRepository {
        CoordinatedInventory canned = new CoordinatedInventory(Map.of(), Map.of(), Map.of());
        boolean called = false;
        Set<String> capturedNames;

        @Override
        public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames) {
            called = true;
            capturedNames = characterNames;
            return canned;
        }
    }

    private static class FakeTpPriceRepository extends TpPriceRepository {
        Map<Integer, PriceQuote> canned = Map.of();

        @Override
        public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) {
            return canned;
        }
    }

    private static class FakeItemRepository extends ItemRepository {
        Map<Integer, ItemInfo> canned = Map.of();

        @Override
        public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) {
            return canned;
        }
    }

    private static class FakeCharacterRepository extends CharacterRepository {
        List<DiscRow> canned = List.of();

        @Override
        public List<DiscRow> loadAllCharacterCrafting() {
            return canned;
        }
    }

    private static class RecordingCraftingPlanner extends CraftingPlanner {
        List<Recipe> capturedRecipes;
        Map<Integer, Integer> capturedSellable;
        Map<Integer, Integer> capturedAccountBound;
        Map<String, Map<Integer, Integer>> capturedCharacterBound;
        List<CharacterCraftingProfile> capturedRoster;
        Map<Integer, PriceQuote> capturedTp;
        CraftingSettings capturedSettings;
        Set<Integer> capturedAllowedRecipeIds;
        MaterialTradeability capturedTradeability;

        /**
         * The classification-aware entry point the service uses for every table calculation
         * (STORY-DOM-021); the shorter overload remains for the JavaFX and Discovery callers.
         */
        @Override
        public Map<Integer, CraftResult> evaluateAllCoordinated(
                List<Recipe> recipes,
                Map<Integer, Integer> sellableInventory,
                Map<Integer, Integer> accountBoundInventory,
                Map<String, Map<Integer, Integer>> characterBoundInventory,
                List<CharacterCraftingProfile> roster,
                Map<Integer, PriceQuote> tp,
                CraftingSettings settings,
                Set<Integer> allowedRecipeIds,
                MaterialTradeability tradeability) {
            this.capturedRecipes = recipes;
            this.capturedSellable = sellableInventory;
            this.capturedAccountBound = accountBoundInventory;
            this.capturedCharacterBound = characterBoundInventory;
            this.capturedRoster = roster;
            this.capturedTp = tp;
            this.capturedSettings = settings;
            this.capturedAllowedRecipeIds = allowedRecipeIds;
            this.capturedTradeability = tradeability;
            return super.evaluateAllCoordinated(recipes, sellableInventory, accountBoundInventory,
                    characterBoundInventory, roster, tp, settings, allowedRecipeIds, tradeability);
        }
    }
}
