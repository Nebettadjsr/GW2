
import craft.CraftResult;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import repo.*;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class CraftingProfitController {

    private final RecipeRepository recipeRepo = new RecipeRepository();
    private final InventoryRepository invRepo  = new InventoryRepository();
    private final TpPriceRepository   tpRepo   = new TpPriceRepository();
    private final ItemRepository      itemRepo = new ItemRepository();

    private final CraftingPlanner planner = new CraftingPlanner();

    // --------- Caches for Details panel (View can ask controller) ----------
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, TpPriceRepository.TpQuote> lastTp = Map.of();


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
        public final int matsSellValueCopper;



        public UiRow(int recipeId, int outputItemId, String outputName, String discipline,
                     int craftableCount, String missingSummary,
                     int buyCostCopper, int matsSellValueCopper,
                     int revenueCopper, int profitCopper, int totalProfitCopper) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.discipline = discipline;
            this.craftableCount = craftableCount;
            this.missingSummary = missingSummary;
            this.buyCostCopper = buyCostCopper;
            this.matsSellValueCopper = matsSellValueCopper;   // NEW
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.totalProfitCopper = totalProfitCopper;
        }

    }

    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings, String search) throws SQLException {

        List<RecipeRepository.Recipe> visibleRecipes;

        if (choice == null || choice.kind == DiscChoice.Kind.ALL) {
            visibleRecipes = recipeRepo.loadRecipes("All"); // DISTINCT aus character_recipes (deine neue Logik)
        } else if (choice.kind == DiscChoice.Kind.DISCIPLINE_ONLY) {
            visibleRecipes = recipeRepo.loadRecipes(choice.discipline); // discipline across all chars
        } else {
            // CHAR_DISCIPLINE
            visibleRecipes = recipeRepo.loadRecipesForCharacter(choice.charName, choice.discipline);
        }

// Full recipe graph for planner (ALL recipes)
        List<RecipeRepository.Recipe> allRecipes = recipeRepo.loadAllRecipes();


        // 2) inventory (bank/materials depending on settings.includeBank)
        Map<Integer, Integer> inv = invRepo.loadInventory(settings.includeBank);

        // 3) collect all itemIds we need prices+names for (outputs + ingredients)
        Set<Integer> itemIds = new HashSet<>();

        // IMPORTANT: use allRecipes (the planner graph), not only visibleRecipes
        for (RecipeRepository.Recipe r : allRecipes) {
            itemIds.add(r.outputItemId);
            for (RecipeRepository.Ingredient ing : r.ingredients) {
                itemIds.add(ing.itemId);
            }
        }

        Map<Integer, TpPriceRepository.TpQuote> tp = tpRepo.loadTpQuotes(itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);
        this.lastTp = tp;
        this.lastItems = items;


        // 5) plan (USE the settings that came from the UI)
        Map<Integer, CraftResult> resultsByRecipeId = planner.evaluateAll(allRecipes, inv, tp, settings);

        // cache results for View details (tree + missing list)
        this.lastResultsByRecipeId = resultsByRecipeId;

        // 6) map to UI rows
        List<UiRow> uiRows = new ArrayList<>();
        for (RecipeRepository.Recipe r : visibleRecipes) {
            CraftResult cr = resultsByRecipeId.get(r.recipeId);
            if (cr == null) continue;
            // Hide clutter: if we would need to BUY something with 0c price, skip this recipe
            if (hasZeroPricedBuy(cr, tp, settings)) continue;
            // If output is not tradable (no TP sell price), don’t show it in the profit table
            if (cr.revenueCopper <= 0) continue;



            var it = items.get(r.outputItemId);
            String name = (it != null && it.name != null && !it.name.isBlank())
                    ? it.name
                    : ("Item " + r.outputItemId);

            String miss = summarizeMissing(cr.missingToBuy, items, tp, settings.allowBuying);

            uiRows.add(new UiRow(
                    r.recipeId,
                    r.outputItemId,
                    name,
                    r.disciplinesText,
                    cr.craftableCount,
                    miss,
                    cr.buyCostCopper,
                    cr.matsSellValueCopper,     // NEW
                    cr.revenueCopper,
                    cr.profitCopper,
                    cr.totalProfitCopper
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

    public int itemSellUnit(int itemId, boolean listingSell) {
        TpPriceRepository.TpQuote q = lastTp.get(itemId);
        if (q == null) return 0;

        Integer v = listingSell ? q.sellUnit : q.buyUnit;
        return (v == null) ? 0 : v;
    }

    public TpPriceRepository.TpQuote tpQuote(int itemId) {
        return lastTp.get(itemId);
    }

    private boolean hasZeroPricedBuy(CraftResult cr,
                                     Map<Integer, TpPriceRepository.TpQuote> tp,
                                     CraftingSettings settings) {

        if (!settings.allowBuying) return false;
        if (cr == null || cr.missingToBuy == null || cr.missingToBuy.isEmpty()) return false;

        for (var e : cr.missingToBuy.entrySet()) {
            int itemId = e.getKey();
            int qty = e.getValue();
            if (qty <= 0) continue;

            TpPriceRepository.TpQuote q = tp.get(itemId);

            // If we must buy it, but we have NO TP row loaded -> treat as not tradable/unknown -> hide
            if (q == null) return true;

            // Buy price mapping (your rule)
            Integer unit = settings.listingBuy ? q.buyUnit : q.sellUnit;

            // If not tradable => DB NULL => unit null (or 0) -> hide
            if (unit == null || unit <= 0) return true;
        }
        return false;
    }


}