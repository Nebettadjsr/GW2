
import application.CraftingProfitService;
import craft.*;
import repo.DiscChoice;
import repo.ItemRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class CraftingProfitController {

    private final CraftingProfitService profitService = new CraftingProfitService();

    // --------- Caches for Details panel (View can ask controller) ----------
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, PriceQuote> lastTp = Map.of();

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

        /**
         * The domain's own {@code CraftResult.totalSellValueCopper}, carried unchanged
         * (STORY-APP-013). Presentation displays this value and never re-derives it from
         * {@link #revenueCopper} and {@link #craftableCount}.
         */
        public final int totalSellValueCopper;
        public final int matsSellValueCopper;
        public final String searchBlob;
        public final boolean calculationAvailable;



        public UiRow(int recipeId, int outputItemId, String outputName, String discipline,
                     int craftableCount, String missingSummary,
                     int buyCostCopper, int matsSellValueCopper,
                     int revenueCopper, int profitCopper, int totalProfitCopper,
                     int totalSellValueCopper,
                     String searchBlob, boolean calculationAvailable) {
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
            this.totalSellValueCopper = totalSellValueCopper;
            this.searchBlob = searchBlob;
            this.calculationAvailable = calculationAvailable;
        }

    }

    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings) throws SQLException {

        CraftingProfitService.ProfitData data = profitService.reload(choice, settings);

        this.lastAllowedRecipeIds = data.visibleRecipes().stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());
        this.lastTp = data.tp();
        this.lastItems = data.items();

        return prepareRows(data.visibleRecipes(), data.allRecipes(), data.resultsByRecipeId(),
                data.items(), data.tp(), settings);
    }

    // Pure result preparation boundary, also used by controller regression tests.
    List<UiRow> prepareRows(List<Recipe> visibleRecipes,
                            List<Recipe> allRecipes,
                            Map<Integer, CraftResult> resultsByRecipeId,
                            Map<Integer, ItemRepository.ItemInfo> items,
                            Map<Integer, PriceQuote> tp,
                            CraftingSettings settings) {
        List<UiRow> uiRows = new ArrayList<>();
        Map<Integer, List<Recipe>> recipesByOutput = new HashMap<>();
        for (Recipe r : allRecipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        for (Recipe r : visibleRecipes) {
            CraftResult cr = resultsByRecipeId.get(r.recipeId);
            if (cr == null) continue;

            var it = items.get(r.outputItemId);
            String baseName = (it != null && it.name != null && !it.name.isBlank())
                    ? it.name
                    : ("Item " + r.outputItemId);

            String name = (r.outputCount > 1)
                    ? (r.outputCount + "x " + baseName)
                    : baseName;

            CraftingResultPresentation presentation = new CraftingResultPresentation(cr, tp, settings);
            String miss = presentation.status.isEmpty()
                    ? summarizeMissing(cr.missingToBuy, items, tp, settings.allowBuying)
                    : presentation.status;
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
                    cr.totalSellValueCopper,
                    searchBlob,
                    presentation.calculationAvailable
            ));
        }

        return uiRows;
    }

    // --------- View helpers ---------

    /** View calls this when a row is selected */
    public CraftResult getResultByRecipeId(int recipeId) {
        return profitService.getResultByRecipeId(recipeId);
    }

    /** As {@link #getResultByRecipeId(int)} but without the lazy recipe-tree build, for callers that only read numbers. */
    public CraftResult getRawResultByRecipeId(int recipeId) {
        return profitService.getRawResultByRecipeId(recipeId);
    }

    /** View calls this for labels in tree/list */
    public String itemName(int itemId) {
        ItemRepository.ItemInfo it = lastItems.get(itemId);
        if (it != null && it.name != null && !it.name.isBlank()) return it.name;
        return "Item " + itemId;
    }

    private String summarizeMissing(Map<Integer, Integer> missing,
                                    Map<Integer, ItemRepository.ItemInfo> items,
                                    Map<Integer, PriceQuote> tp,
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

            PriceQuote q = tp.get(itemId);
            boolean noTp = (q == null || q.sellUnit == null);

            parts.add(name + " x" + qty + (noTp ? " (no TP)" : ""));
        }

        if (missing.size() > 2) parts.add("...");
        return (allowBuying ? "To buy: " : "Missing: ") + String.join(", ", parts);
    }

    public int itemSellUnit(int itemId, boolean listingSell) {
        PriceQuote q = lastTp.get(itemId);
        if (q == null) return 0;

        Integer v = listingSell ? q.sellUnit : q.buyUnit;
        return (v == null) ? 0 : v;
    }

    public PriceQuote tpQuote(int itemId) {
        return lastTp.get(itemId);
    }

    private String buildSearchBlob(Recipe recipe,
                                   Map<Integer, ItemRepository.ItemInfo> items,
                                   Map<Integer, List<Recipe>> recipesByOutput,
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

        for (Ingredient ing : recipe.ingredients) {
            ItemRepository.ItemInfo ingInfo = items.get(ing.itemId);
            if (ingInfo != null && ingInfo.name != null) {
                sb.append(ingInfo.name).append(' ');
            }

            List<Recipe> subRecipes = recipesByOutput.get(ing.itemId);
            if (subRecipes != null && !subRecipes.isEmpty()) {
                Recipe allowedSubRecipe = null;
                for (Recipe sub : subRecipes) {
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
