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


        public UiRow(int recipeId, int outputItemId, String outputName,
                     int recipeLevel,
                     int buyCostCopper, int revenueCopper, int profitCopper,
                     String missingSummary) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.recipeLevel = recipeLevel;
            this.buyCostCopper = buyCostCopper;
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.missingSummary = missingSummary;
        }
    }

    /**
     * Loads DISCOVERABLE recipes you still miss for this char+discipline.
     * Discipline can be "All" or a concrete one.
     */
    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings, String search) throws SQLException {

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

        // 6) evaluate (same engine as profit view)
        Map<Integer, CraftResult> results = planner.evaluateAll(allRecipes, inv, tp, settings);
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
            out.add(new UiRow(
                    r.recipeId,
                    r.outputItemId,
                    name,
                    lvl,
                    missingBuyCost,
                    cr.revenueCopper,
                    cr.profitCopper,
                    miss
            ));
        }

        // search filter
        if (search != null && !search.isBlank()) {
            String s = search.trim().toLowerCase();
            out = out.stream()
                    .filter(x -> x.outputName != null && x.outputName.toLowerCase().contains(s))
                    .collect(Collectors.toList());
        }

        return out;
    }

    // --- details helpers (same style as your profit controller) ---
    public CraftResult getResultByRecipeId(int recipeId) { return lastResultsByRecipeId.get(recipeId); }

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

}