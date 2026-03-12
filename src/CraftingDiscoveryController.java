import craft.*;
import repo.*;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class CraftingDiscoveryController {

    private final RecipeRepository recipeRepo = new RecipeRepository();
    private final InventoryRepository invRepo  = new InventoryRepository();
    private final TpPriceRepository   tpRepo   = new TpPriceRepository();
    private final ItemRepository      itemRepo = new ItemRepository();

    private final CraftingPlanner planner = new CraftingPlanner();
    private List<RecipeRepository.Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings = null;

    // caches for right-side details
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, TpPriceRepository.TpQuote> lastTp = Map.of();

    public static class UiRow {
        public final int recipeId;
        public final int outputItemId;
        public final String outputName;
        public final int recipeLevel;     // NEW
        public final int buyCostCopper;     // cost to buy missing mats (using inventory first)
        public final int revenueCopper;     // output sell price (TP) - informational
        public final int profitCopper;      // optional, also informational
        public final String missingSummary;
        public final String searchBlob;

        public UiRow(int recipeId, int outputItemId, String outputName,
                     int recipeLevel,
                     int buyCostCopper, int revenueCopper, int profitCopper,
                     String missingSummary,
                     String searchBlob) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.recipeLevel = recipeLevel;
            this.buyCostCopper = buyCostCopper;
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.missingSummary = missingSummary;
            this.searchBlob = searchBlob;
        }
    }

    /**
     * Loads DISCOVERABLE recipes you still miss for this char+discipline.
     * Discipline can be "All" or a concrete one.
     */
    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings) throws SQLException {

        String charName = (choice == null) ? null : choice.charName;
        String discipline = (choice == null) ? "All" : choice.discipline; // or null->All depending on your DiscChoice
        int maxLevel = (choice != null) ? choice.rating : Integer.MAX_VALUE;


        // 1) all recipes for planner graph
        CraftingGraph graph;
        try {
            CraftingGraphCache graphCache = new CraftingGraphCache(recipeRepo);
            graph = graphCache.load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }

        List<RecipeRepository.Recipe> allRecipes = graph.getRecipes();

        // 2) missing discoverable recipe ids
        List<Integer> missingIds = recipeRepo.loadMissingDiscoverableRecipeIdsForCharacter(charName, discipline);

        if (missingIds.isEmpty()) return List.of();

        Set<Integer> missingSet = new HashSet<>(missingIds);

        // 3) visible recipes are subset of allRecipes (so we keep ingredients)
        List<RecipeRepository.Recipe> visibleRecipes = allRecipes.stream()
                .filter(r -> missingSet.contains(r.recipeId))
                .collect(Collectors.toList());

        // Only show recipes that are discoverable NOW for this character level
        if (choice != null) {
            visibleRecipes = visibleRecipes.stream()
                    .filter(r -> r.minRating <= maxLevel)
                    .toList();
        }

        // 4) inventory
        Map<Integer,Integer> inv = settings.useOwnMats
                                   ? invRepo.loadOwnedInventory()
                                   : Map.of();

        // 5) collect all item ids needed (use allRecipes for planner completeness)
        Set<Integer> itemIds = new HashSet<>();
        for (RecipeRepository.Recipe r : allRecipes) {
            itemIds.add(r.outputItemId);
            for (RecipeRepository.Ingredient ing : r.ingredients) itemIds.add(ing.itemId);
        }

        Map<Integer, TpPriceRepository.TpQuote> tp = tpRepo.loadTpQuotes(itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);
        this.lastTp = tp;
        this.lastItems = items;

        this.lastAllRecipes = allRecipes;
        this.lastSettings = settings;

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe rr : allRecipes) {
            recipesByOutput
                    .computeIfAbsent(rr.outputItemId, k -> new ArrayList<>())
                    .add(rr);
        }

        // 6) evaluate (same engine as profit view)
        Map<Integer, CraftResult> results = planner.evaluateAll(visibleRecipes, inv, tp, settings);
        this.lastResultsByRecipeId = results;

        // 7) map to UI
        List<UiRow> out = new ArrayList<>();
        for (RecipeRepository.Recipe r : visibleRecipes) {
            CraftResult cr = results.get(r.recipeId);
            if (cr == null) continue;

            var it = items.get(r.outputItemId);
            String name = (it != null && it.name != null && !it.name.isBlank())
                    ? it.name
                    : ("Item " + r.outputItemId);

            String miss = summarizeMissing(cr.missingToBuy, items, tp, settings.allowBuying);

            int lvl = r.minRating;
            int missingBuyCost = cr.buyCostCopper; // PER 1 craft ✅
            String searchBlob = buildSearchBlob(r, items, recipesByOutput, new HashSet<>());

            out.add(new UiRow(
                    r.recipeId,
                    r.outputItemId,
                    name,
                    lvl,
                    missingBuyCost,
                    cr.revenueCopper,
                    cr.profitCopper,
                    miss,
                    searchBlob
            ));
        }

        return out;
    }

    // --- details helpers (same style as your profit controller) ---
    public CraftResult getResultByRecipeId(int recipeId) {
        CraftResult cr = lastResultsByRecipeId.get(recipeId);
        if (cr == null) return null;

        if (cr.tree != null) {
            return cr;
        }

        Node lazyTree = buildTreeForRecipeId(recipeId);

        CraftResult enriched = new CraftResult(
                cr.outputItemId,
                cr.discipline,
                cr.craftableCount,
                cr.missingToBuy,
                cr.missingToBuyOne,
                cr.buyCostCopper,
                cr.matsSellValueCopper,
                cr.revenueCopper,
                cr.profitCopper,
                cr.totalProfitCopper,
                lazyTree
        );

        Map<Integer, CraftResult> copy = new HashMap<>(lastResultsByRecipeId);
        copy.put(recipeId, enriched);
        lastResultsByRecipeId = copy;

        return enriched;
    }

    public String itemName(int itemId) {
        ItemRepository.ItemInfo it = lastItems.get(itemId);
        if (it != null && it.name != null && !it.name.isBlank()) return it.name;
        return "Item " + itemId;
    }

    public int itemSellUnit(int itemId, boolean listingSell) {
        TpPriceRepository.TpQuote q = lastTp.get(itemId);
        if (q == null) return 0;
        Integer v = listingSell ? q.sellUnit : q.buyUnit;
        return (v == null) ? 0 : v;
    }

    private String summarizeMissing(Map<Integer, Integer> missing,
                                    Map<Integer, ItemRepository.ItemInfo> items,
                                    Map<Integer, TpPriceRepository.TpQuote> tp,
                                    boolean allowBuying) {
        if (missing == null || missing.isEmpty()) return allowBuying ? "To buy: 0" : "Missing: 0";

        List<String> parts = new ArrayList<>();
        int i = 0;
        for (var e : missing.entrySet()) {
            if (i++ >= 2) break;
            int itemId = e.getKey();
            int qty = e.getValue();

            String name = (items.containsKey(itemId) && items.get(itemId).name != null)
                    ? items.get(itemId).name
                    : ("Item " + itemId);

            TpPriceRepository.TpQuote q = tp.get(itemId);
            boolean noTp = (q == null || q.sellUnit == null);

            parts.add(name + " x" + qty + (noTp ? " (no TP)" : ""));
        }
        if (missing.size() > 2) parts.add("...");
        return (allowBuying ? "To buy: " : "Missing: ") + String.join(", ", parts);
    }

    private Node buildTreeForRecipeId(int recipeId) {
        if (lastAllRecipes == null || lastAllRecipes.isEmpty() || lastSettings == null) {
            return null;
        }

        RecipeRepository.Recipe target = null;
        for (RecipeRepository.Recipe r : lastAllRecipes) {
            if (r.recipeId == recipeId) {
                target = r;
                break;
            }
        }

        if (target == null) return null;

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe r : lastAllRecipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        PlannerContext ctx = new PlannerContext(recipesByOutput, lastTp, lastSettings);
        RecipeTreeBuilder treeBuilder = new RecipeTreeBuilder();

        return treeBuilder.buildTree(target, ctx);
    }

    private String buildSearchBlob(RecipeRepository.Recipe recipe,
                                   Map<Integer, ItemRepository.ItemInfo> items,
                                   Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput,
                                   Set<Integer> visited) {
        if (recipe == null) return "";

        StringBuilder sb = new StringBuilder();

        if (!visited.add(recipe.recipeId)) {
            return "";
        }

        ItemRepository.ItemInfo out = items.get(recipe.outputItemId);
        if (out != null && out.name != null) {
            sb.append(out.name).append(' ');
        }

        for (RecipeRepository.Ingredient ing : recipe.ingredients) {
            ItemRepository.ItemInfo ingInfo = items.get(ing.itemId);
            if (ingInfo != null && ingInfo.name != null) {
                sb.append(ingInfo.name).append(' ');
            }

            List<RecipeRepository.Recipe> subRecipes = recipesByOutput.get(ing.itemId);
            if (subRecipes != null && !subRecipes.isEmpty()) {
                sb.append(buildSearchBlob(subRecipes.get(0), items, recipesByOutput, visited)).append(' ');
            }
        }

        return sb.toString().toLowerCase();
    }
}