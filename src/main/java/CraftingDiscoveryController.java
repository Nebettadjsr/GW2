import application.CraftingDiscoveryService;
import craft.*;
import repo.DiscChoice;
import repo.ItemRepository;

import java.sql.SQLException;
import java.util.*;

public class CraftingDiscoveryController {

    private final CraftingDiscoveryService discoveryService = new CraftingDiscoveryService();

    private Set<Integer> lastAllowedRecipeIds = Collections.emptySet();

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
        public final boolean calculationAvailable;

        public UiRow(int recipeId, int outputItemId, String outputName,
                     int recipeLevel,
                     int buyCostCopper, int revenueCopper, int profitCopper,
                     String missingSummary,
                     String searchBlob, boolean calculationAvailable) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputName = outputName;
            this.recipeLevel = recipeLevel;
            this.buyCostCopper = buyCostCopper;
            this.revenueCopper = revenueCopper;
            this.profitCopper = profitCopper;
            this.missingSummary = missingSummary;
            this.searchBlob = searchBlob;
            this.calculationAvailable = calculationAvailable;
        }
    }

    /**
     * Loads DISCOVERABLE recipes you still miss for this char+discipline.
     * Discipline can be "All" or a concrete one.
     */
    public List<UiRow> reload(DiscChoice choice, CraftingSettings settings, String selectedCharacterName) throws SQLException {

        CraftingDiscoveryService.DiscoveryData data = discoveryService.reload(choice, settings, selectedCharacterName);

        this.lastAllowedRecipeIds = data.visibleRecipes().stream()
                .map(r -> r.recipeId)
                .collect(java.util.stream.Collectors.toSet());

        return prepareRows(data.visibleRecipes(), data.allRecipes(), data.resultsByRecipeId(),
                data.items(), data.tp(), settings);
    }

    // Pure result preparation boundary, also used by controller regression tests.
    List<UiRow> prepareRows(List<Recipe> visibleRecipes,
                            List<Recipe> allRecipes,
                            Map<Integer, CraftResult> results,
                            Map<Integer, ItemRepository.ItemInfo> items,
                            Map<Integer, PriceQuote> tp,
                            CraftingSettings settings) {
        Map<Integer, List<Recipe>> recipesByOutput = new HashMap<>();
        for (Recipe rr : allRecipes) {
            recipesByOutput
                    .computeIfAbsent(rr.outputItemId, k -> new ArrayList<>())
                    .add(rr);
        }

        // map to UI
        List<UiRow> out = new ArrayList<>();
        for (Recipe r : visibleRecipes) {
            CraftResult cr = results.get(r.recipeId);
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

            int lvl = r.minRating;
            int missingBuyCost = cr.buyCostCopper;
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
                    searchBlob,
                    presentation.calculationAvailable
            ));
        }

        return out;
    }
    // --- details helpers (same style as your profit controller) ---
    public CraftResult getResultByRecipeId(int recipeId) {
        return discoveryService.getResultByRecipeId(recipeId);
    }

    public String itemName(int itemId) {
        return discoveryService.itemName(itemId);
    }

    public int itemSellUnit(int itemId, boolean listingSell) {
        return discoveryService.itemSellUnit(itemId, listingSell);
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
