
import craft.*;
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
    private List<RecipeRepository.Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings = null;

    // --------- Caches for Details panel (View can ask controller) ----------
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, TpPriceRepository.TpQuote> lastTp = Map.of();

    private Set<Integer> lastAllowedRecipeIds = Collections.emptySet();


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
        public final String searchBlob;



        public UiRow(int recipeId, int outputItemId, String outputName, String discipline,
                     int craftableCount, String missingSummary,
                     int buyCostCopper, int matsSellValueCopper,
                     int revenueCopper, int profitCopper, int totalProfitCopper,
                     String searchBlob) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.discipline = discipline;
            this.craftableCount = craftableCount;
            this.missingSummary = missingSummary;
            this.buyCostCopper = buyCostCopper;
            this.matsSellValueCopper = matsSellValueCopper;
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.totalProfitCopper = totalProfitCopper;
            this.searchBlob = searchBlob;
        }

    }

    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings) throws SQLException {

        List<RecipeRepository.Recipe> visibleRecipes;
        if (choice == null || choice.kind == DiscChoice.Kind.ALL) {
            visibleRecipes = recipeRepo.loadRecipes("All");
        } else if (choice.kind == DiscChoice.Kind.DISCIPLINE_ONLY) {
            visibleRecipes = recipeRepo.loadRecipes(choice.discipline);
        } else { // CHAR_DISCIPLINE
            visibleRecipes = recipeRepo.loadRecipesForCharacter(choice.charName, choice.discipline);
        }

        Set<Integer> allowedRecipeIds = visibleRecipes.stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        this.lastAllowedRecipeIds = allowedRecipeIds;

        CraftingGraph graph;
        try {
            CraftingGraphCache graphCache = new CraftingGraphCache(recipeRepo);
            graph = graphCache.load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }

        List<RecipeRepository.Recipe> allRecipes = graph.getRecipes();

        Map<Integer,Integer> inv = settings.useOwnMats
                ? invRepo.loadOwnedInventory()
                : Map.of();

        Set<Integer> itemIds = new HashSet<>();
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
        this.lastAllRecipes = allRecipes;
        this.lastSettings = settings;

        Map<Integer, CraftResult> resultsByRecipeId =
                planner.evaluateAll(allRecipes, inv, tp, settings, allowedRecipeIds);

        this.lastResultsByRecipeId = resultsByRecipeId;

        List<UiRow> uiRows = new ArrayList<>();
        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe r : allRecipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        for (RecipeRepository.Recipe r : visibleRecipes) {
            CraftResult cr = resultsByRecipeId.get(r.recipeId);
            if (cr == null) continue;
            if (hasZeroPricedBuy(cr, tp, settings)) continue;
            if (cr.revenueCopper <= 0) continue;

            var it = items.get(r.outputItemId);
            String baseName = (it != null && it.name != null && !it.name.isBlank())
                    ? it.name
                    : ("Item " + r.outputItemId);

            String name = (r.outputCount > 1)
                    ? (r.outputCount + "x " + baseName)
                    : baseName;

            String miss = summarizeMissing(cr.missingToBuy, items, tp, settings.allowBuying);
            String searchBlob = buildSearchBlob(r, items, recipesByOutput, new HashSet<>());

            uiRows.add(new UiRow(
                    r.recipeId,
                    r.outputItemId,
                    name,
                    r.disciplinesText,
                    cr.craftableCount,
                    miss,
                    cr.buyCostCopper,
                    cr.matsSellValueCopper,
                    cr.revenueCopper,
                    cr.profitCopper,
                    cr.totalProfitCopper,
                    searchBlob
            ));
        }

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

        PlannerContext ctx = new PlannerContext(
                recipesByOutput,
                lastTp,
                lastSettings,
                lastAllowedRecipeIds
        );

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
                RecipeRepository.Recipe allowedSubRecipe = null;
                for (RecipeRepository.Recipe sub : subRecipes) {
                    if (lastAllowedRecipeIds.contains(sub.recipeId)) {
                        allowedSubRecipe = sub;
                        break;
                    }
                }

                if (allowedSubRecipe != null) {
                    sb.append(buildSearchBlob(allowedSubRecipe, items, recipesByOutput, visited)).append(' ');
                }
            }
        }

        return sb.toString().toLowerCase();
    }
}