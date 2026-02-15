import craft.CraftResult;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import repo.*;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class CraftingProfitController {

    private final RecipeRepository recipeRepo = new RecipeRepository();
    private final InventoryRepository invRepo = new InventoryRepository();
    private final TpPriceRepository tpRepo = new TpPriceRepository();
    private final ItemRepository itemRepo = new ItemRepository();

    private final CraftingPlanner planner = new CraftingPlanner();

    // --------- Caches for Details panel (View can ask controller) ----------
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();

    public static class UiRow {
        public final int recipeId;       // NEW
        public final int outputItemId;
        public final String outputName;
        public final String discipline;

        public final int craftableCount;
        public final String missingSummary;

        public final int buyCostCopper;
        public final int revenueCopper;
        public final int profitCopper;
        public final int totalProfitCopper;


        public UiRow(int recipeId, int outputItemId, String outputName, String discipline,
                     int craftableCount, String missingSummary,
                     int buyCostCopper, int revenueCopper, int profitCopper, int totalProfitCopper) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.discipline = discipline;
            this.craftableCount = craftableCount;
            this.missingSummary = missingSummary;
            this.buyCostCopper = buyCostCopper;
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.totalProfitCopper = totalProfitCopper;
        }
    }

    public List<UiRow> reload(String disc, CraftingSettings settings, String search) throws SQLException {

        // 1) recipes (filter handled by SQL when disc != "All")
        List<RecipeRepository.Recipe> recipes = recipeRepo.loadRecipes(disc);

        // 2) inventory (bank/materials depending on settings.includeBank)
        Map<Integer, Integer> inv = invRepo.loadInventory(settings.includeBank);

        // 3) collect all itemIds we need prices+names for (outputs + ingredients)
        Set<Integer> itemIds = new HashSet<>();
        for (RecipeRepository.Recipe r : recipes) {
            itemIds.add(r.outputItemId);
            for (RecipeRepository.Ingredient ing : r.ingredients) itemIds.add(ing.itemId);
        }

        // 4) load tp prices + item names
        Map<Integer, TpPriceRepository.TpQuote> tp = tpRepo.loadTpQuotes(itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);

        // cache items for View details (names)
        this.lastItems = items;

        // 5) plan (USE the settings that came from the UI)
        Map<Integer, CraftResult> resultsByRecipeId = planner.evaluateAll(recipes, inv, tp, settings);

        // cache results for View details (tree + missing list)
        this.lastResultsByRecipeId = resultsByRecipeId;

        // 6) map to UI rows
        List<UiRow> uiRows = new ArrayList<>();
        for (RecipeRepository.Recipe r : recipes) {
            CraftResult cr = resultsByRecipeId.get(r.recipeId);
            if (cr == null) continue;

            var it = items.get(r.outputItemId);
            String name = (it != null && it.name != null && !it.name.isBlank())
                    ? it.name
                    : ("Item " + r.outputItemId);

            String miss = summarizeMissing(cr.missingToBuy, items, tp, settings.allowBuying);

            uiRows.add(new UiRow(
                    r.recipeId,
                    r.outputItemId,
                    name,
                    r.disciplinesText,          // keep whatever you already had working here
                    cr.craftableCount,
                    miss,
                    cr.buyCostCopper,        // TOTAL buy cost
                    cr.revenueCopper,        // per craft sell price
                    cr.profitCopper,         // per craft profit
                    cr.totalProfitCopper     // TOTAL profit
            ));
        }

        // 7) search filter
        if (search != null && !search.isBlank()) {
            String s = search.trim().toLowerCase();
            uiRows = uiRows.stream()
                    .filter(x -> x.outputName != null && x.outputName.toLowerCase().contains(s))
                    .collect(Collectors.toList());
        }

        // 8) budget + craftable filters
        if (!settings.allowBuying) {
            uiRows = uiRows.stream()
                    .filter(x -> x.craftableCount > 0)
                    .collect(Collectors.toList());
        } else if (settings.maxBuyCopper > 0) {
            int max = settings.maxBuyCopper;
            uiRows = uiRows.stream()
                    .filter(x -> x.craftableCount > 0)
                    .filter(x -> x.buyCostCopper <= max)
                    .collect(Collectors.toList());
        }

        return uiRows;
    }


    // --------- View helpers ---------

    /** View calls this when a row is selected */
    public CraftResult getResultByRecipeId(int recipeId) {
        return lastResultsByRecipeId.get(recipeId);
    }

    /** View calls this for labels in tree/list */
    public String itemName(int itemId) {
        ItemRepository.ItemInfo it = lastItems.get(itemId);
        if (it != null && it.name != null && !it.name.isBlank()) return it.name;
        return "Item " + itemId;
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