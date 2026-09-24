package web;

import craft.CraftResult;
import craft.PriceQuote;
import craft.Recipe;
import repo.ItemRepository;
import web.dto.CraftingRowDto;
import web.dto.MissingItemDto;
import web.dto.TradingPostQuoteDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Copies the recipe/result pair both crafting calculation routes return into the shared
 * {@link CraftingRowDto} (STORY-API-001 Profit, STORY-API-002 Discovery).
 *
 * <p>This class only copies. It performs no crafting calculation, derives no total, and applies no
 * display rule - every number it emits is the one the domain produced, and no row is dropped
 * because its calculation was blocked or its result unavailable. It is stateless, so it is safe to
 * share across concurrent requests.
 */
final class CraftingRowMapper {

    private CraftingRowMapper() {}

    /**
     * Maps every visible recipe, in the order the application service returned them, pairing each
     * with its result from {@code results} (absent results become {@code resultAvailable=false}
     * rows rather than omissions).
     */
    static List<CraftingRowDto> toRows(List<Recipe> visibleRecipes,
                                       Map<Integer, CraftResult> results,
                                       Map<Integer, ItemRepository.ItemInfo> items,
                                       Map<Integer, PriceQuote> tp) {

        List<CraftingRowDto> rows = new ArrayList<>(visibleRecipes.size());
        for (Recipe recipe : visibleRecipes) {
            rows.add(toRow(recipe, results.get(recipe.recipeId), items, tp));
        }
        return rows;
    }

    private static CraftingRowDto toRow(Recipe recipe,
                                        CraftResult result,
                                        Map<Integer, ItemRepository.ItemInfo> items,
                                        Map<Integer, PriceQuote> tp) {
        if (result == null) {
            return new CraftingRowDto(
                    recipe.recipeId, recipe.outputItemId, itemName(recipe.outputItemId, items),
                    recipe.outputCount, recipe.disciplinesText, recipe.minRating,
                    false, null, null, null, null, null, null, null,
                    toQuote(tp.get(recipe.outputItemId)), List.of(), List.of());
        }

        return new CraftingRowDto(
                recipe.recipeId,
                recipe.outputItemId,
                itemName(recipe.outputItemId, items),
                recipe.outputCount,
                recipe.disciplinesText,
                recipe.minRating,
                true,
                result.craftableCount,
                result.buyCostCopper,
                result.matsSellValueCopper,
                result.revenueCopper,
                result.profitCopper,
                result.totalProfitCopper,
                result.blockedReason == null ? null : result.blockedReason.name(),
                toQuote(tp.get(recipe.outputItemId)),
                toMissing(result.missingToBuy, items, tp),
                toMissing(result.missingToBuyOne, items, tp));
    }

    /** Sorted by item id purely so the response is stable; the domain's maps are unordered. */
    private static List<MissingItemDto> toMissing(Map<Integer, Integer> missing,
                                                  Map<Integer, ItemRepository.ItemInfo> items,
                                                  Map<Integer, PriceQuote> tp) {

        if (missing == null || missing.isEmpty()) return List.of();

        List<MissingItemDto> out = new ArrayList<>(missing.size());
        for (Map.Entry<Integer, Integer> e : missing.entrySet()) {
            out.add(new MissingItemDto(
                    e.getKey(), itemName(e.getKey(), items), e.getValue(), toQuote(tp.get(e.getKey()))));
        }
        out.sort(Comparator.comparingInt(MissingItemDto::itemId));
        return out;
    }

    private static TradingPostQuoteDto toQuote(PriceQuote quote) {
        return quote == null ? null : new TradingPostQuoteDto(quote.buyUnit, quote.sellUnit);
    }

    /** Null when the item is not in the loaded item set, so the caller can tell "unknown" apart. */
    private static String itemName(int itemId, Map<Integer, ItemRepository.ItemInfo> items) {
        ItemRepository.ItemInfo info = items.get(itemId);
        return info == null ? null : info.name;
    }
}
