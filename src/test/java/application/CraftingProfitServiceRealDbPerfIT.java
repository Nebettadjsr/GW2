package application;

import craft.CharacterCraftingProfile;
import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
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
import util.CoinUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * STORY-PERF-001 diagnostic: attributes Crafting Profit's All-scope reload time to its individual
 * pipeline stages (recipe load, graph cache load, TP/item load, roster/inventory load, planner
 * simulation) against the developer's real PostgreSQL database (no {@code gw2tool.test.schema}
 * override, so {@code repo.Db#open()} hits the same connection the running application uses),
 * using the default settings {@code CraftingProfitView} opens with. This inlines the same
 * sequence as {@link CraftingProfitService#reload}/{@code CraftingProfitView}'s reload thread
 * purely to insert per-stage timers; it does not reimplement any calculation. Prints timings and
 * row counts only; not part of the default {@code ./mvnw test} run (excluded by its {@code IT}
 * suffix, per {@code TEST_STRATEGY.md} §34) and asserts nothing, since its purpose is
 * evidence-gathering, not pass/fail verification.
 *
 * <p>Run explicitly: {@code ./mvnw test -Dtest=CraftingProfitServiceRealDbPerfIT}
 */
class CraftingProfitServiceRealDbPerfIT {

    @Test
    void reportsStageTimingsForAllScopeOnRealDatabase() throws Exception {
        CraftingSettings settings = new CraftingSettings(
                true,   // use own mats (view default)
                false,  // allow buying (view default)
                CoinUtils.parseToCopper("1g"),
                false,  // instant sell (view default)
                false,  // instant buy (view default)
                true    // daily items treated as buy (view default)
        );

        RecipeRepository recipeRepo = new RecipeRepository();
        InventoryRepository invRepo = new InventoryRepository();
        TpPriceRepository tpRepo = new TpPriceRepository();
        ItemRepository itemRepo = new ItemRepository();
        CharacterRepository charRepo = new CharacterRepository();
        CraftingGraphCache graphCache = new CraftingGraphCache(recipeRepo);
        CraftingPlanner planner = new CraftingPlanner();

        // Runs the identical sequence twice in the same JVM/connection: "first" (cold JIT, cold
        // OS/DB cache) vs "repeat" (warm JIT, warm OS/DB cache, warm CraftingGraphCache) -
        // STORY-PERF-001 acceptance criteria requires both, not just a single cold run.
        runOnce("FIRST", recipeRepo, invRepo, tpRepo, itemRepo, charRepo, graphCache, planner, settings);
        runOnce("REPEAT", recipeRepo, invRepo, tpRepo, itemRepo, charRepo, graphCache, planner, settings);
    }

    private void runOnce(String label,
                          RecipeRepository recipeRepo,
                          InventoryRepository invRepo,
                          TpPriceRepository tpRepo,
                          ItemRepository itemRepo,
                          CharacterRepository charRepo,
                          CraftingGraphCache graphCache,
                          CraftingPlanner planner,
                          CraftingSettings settings) throws Exception {

        System.out.println("=== STORY-PERF-001 backend stage timing (real DB, All scope, " + label + ") ===");

        long overallStart = System.nanoTime();
        long t;

        t = System.nanoTime();
        List<Recipe> visibleRecipes = recipeRepo.loadRecipes("All");
        log("visibleRecipes load (recipeRepo.loadRecipes)", t, "count=" + visibleRecipes.size());

        t = System.nanoTime();
        CraftingGraph graph = graphCache.load();
        List<Recipe> allRecipes = graph.getRecipes();
        log("graph cache load", t, "count=" + allRecipes.size());

        t = System.nanoTime();
        Set<Integer> itemIds = new HashSet<>();
        for (Recipe r : allRecipes) {
            itemIds.add(r.outputItemId);
            for (Ingredient ing : r.ingredients) itemIds.add(ing.itemId);
        }
        Map<Integer, PriceQuote> tp = tpRepo.loadTpQuotes(itemIds);
        log("tp price load", t, "itemIds=" + itemIds.size() + " quotes=" + tp.size());

        t = System.nanoTime();
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);
        log("item load", t, "items=" + items.size());

        t = System.nanoTime();
        Map<String, Map<String, Integer>> ratingByCharacter = new HashMap<>();
        for (CharacterRepository.DiscRow row : charRepo.loadAllCharacterCrafting()) {
            ratingByCharacter.computeIfAbsent(row.charName, k -> new HashMap<>()).put(row.discipline, row.rating);
        }
        List<CharacterCraftingProfile> roster = new ArrayList<>();
        for (var e : ratingByCharacter.entrySet()) {
            roster.add(new CharacterCraftingProfile(e.getKey(), e.getValue()));
        }
        log("roster build (character_crafting)", t, "characters=" + roster.size());

        t = System.nanoTime();
        Set<String> names = roster.stream().map(CharacterCraftingProfile::name).collect(Collectors.toSet());
        InventoryRepository.CoordinatedInventory inv = invRepo.loadOwnedInventoryForCharacters(names);
        log("coordinated inventory load", t,
                "sellable=" + inv.sellable().size()
                        + " accountBound=" + inv.accountBound().size()
                        + " characterBound=" + inv.characterBound().values().stream().mapToInt(Map::size).sum());

        Set<Integer> allowedRecipeIds = visibleRecipes.stream().map(r -> r.recipeId).collect(Collectors.toSet());


        t = System.nanoTime();
        Map<Integer, CraftResult> results = planner.evaluateAllCoordinated(
                allRecipes, inv.sellable(), inv.accountBound(), inv.characterBound(), roster, tp, settings, allowedRecipeIds);
        log("planner.evaluateAllCoordinated", t, "results=" + results.size());
        log(label + " TOTAL (navigation-equivalent backend work)", overallStart, "");
    }

    private static void log(String stage, long startNanos, String detail) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println(stage + ": " + ms + " ms (" + detail + ")");
    }
}
