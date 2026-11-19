package web;

import application.icons.ItemIconUrls;
import craft.CraftResult;
import craft.PriceQuote;
import craft.Recipe;
import craft.MaterialPurchaseCost;
import repo.ItemRepository;
import web.dto.CraftingRowDto;
import web.dto.ExpectedYieldDto;
import web.dto.CurrencyCostsDto;
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
 * because its calculation was blocked or its result unavailable. {@code totalSellValueCopper} and
 * {@code totalProfitCopper} in particular are read off the result rather than multiplied out of
 * revenue, profit and the craftable count here. It is stateless, so it is safe to share across
 * concurrent requests.
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

    /**
     * Maps one recipe and its result, for a route that reports a single row - the resolution-detail
     * routes (STORY-API-008) - so that row is the same projection, field for field, as the one the
     * table routes report for the same pair.
     */
    static CraftingRowDto toRow(Recipe recipe,
                                CraftResult result,
                                Map<Integer, ItemRepository.ItemInfo> items,
                                Map<Integer, PriceQuote> tp) {
        return toRow(recipe, result, items, tp, false);
    }

    /** As above; {@code detail} adds the selected-result-only Research Note recipe comparison. */
    static CraftingRowDto toRow(Recipe recipe,
                                CraftResult result,
                                Map<Integer, ItemRepository.ItemInfo> items,
                                Map<Integer, PriceQuote> tp,
                                boolean detail) {
        if (result == null) {
            return new CraftingRowDto(
                    recipe.recipeId, recipe.outputItemId, itemName(recipe.outputItemId, items),
                    recipe.outputCount, recipe.disciplinesText, recipe.minRating,
                    false, null, null, null, null, null, null, null, null, null,
                    toQuote(tp.get(recipe.outputItemId)), List.of(), List.of(),
                    ingredientNames(recipe, items),
                    iconUrl(recipe.outputItemId, items), false, null, forgeHint(recipe, null));
        }

        // WORK_LIMIT with no accepted craft: the plan's costs were never established, so they are unknown (null),
        // not zero. The output quote and revenue stay; any fully accepted prefix keeps its exact figures.
        boolean unknownEconomics = result.blockedReason == craft.BlockedReason.WORK_LIMIT && result.craftableCount == 0;
        boolean profitUnknown = result.profitUnknown;
        return new CraftingRowDto(
                recipe.recipeId,
                recipe.outputItemId,
                itemName(recipe.outputItemId, items),
                recipe.outputCount,
                recipe.disciplinesText,
                recipe.minRating,
                true,
                result.craftableCount,
                unknownEconomics ? null : result.buyCostCopper,
                unknownEconomics ? null : result.matsSellValueCopper,
                unknownEconomics ? null : result.totalMatsSellValueCopper,
                result.revenueCopper,
                unknownEconomics || profitUnknown ? null : result.profitCopper,
                unknownEconomics ? null : result.totalSellValueCopper,
                unknownEconomics || profitUnknown ? null : result.totalProfitCopper,
                result.blockedReason == null ? null : result.blockedReason.name(),
                toQuote(tp.get(recipe.outputItemId)),
                toMissing(result.missingToBuy, result.materialPurchaseCosts, items, tp),
                toMissing(result.missingToBuyOne, result.materialPurchaseCostsOne, items, tp),
                ingredientNames(recipe, items),
                iconUrl(recipe.outputItemId, items),
                result.unvaluedOwnedInput,
                toCurrencyCosts(result, items, detail),
                forgeHint(recipe, result));
    }

    private static ExpectedYieldDto forgeHint(Recipe recipe, CraftResult result) {
        boolean estimated = result != null && result.estimated;
        if (!recipe.isMysticForge() && !estimated) return null;
        craft.ExpectedYield y = recipe.expectedYield;
        return new ExpectedYieldDto(recipe.isMysticForge() ? "MYSTIC_FORGE" : null, estimated,
                y == null ? null : y.mean().toPlainString(),
                y == null ? null : y.min(), y == null ? null : y.max(),
                result == null || result.expectedSellableUnitsTotal == null ? null
                        : result.expectedSellableUnitsTotal.toPlainString(),
                result == null || y == null ? null : result.guaranteedSellableUnitsTotal,
                result == null ? 0 : result.reusedSeedUnits);
    }

    /** Labels for currencies this application recognises; any other id keeps its exact number. */
    private static final Map<Integer, String> CURRENCY_NAMES = craft.KnownCurrencies.names();

    static String currencyName(int currencyId) {
        return CURRENCY_NAMES.getOrDefault(currencyId, "Currency " + currencyId);
    }

    /**
     * Copies the domain's exact currency spending; no valuation or break-even figure is derived (deferred).
     */
    private static CurrencyCostsDto toCurrencyCosts(CraftResult result,
                                                    Map<Integer, ItemRepository.ItemInfo> items, boolean detail) {
        Map<Integer, Integer> first = result.currencyPerCraft == null ? Map.of() : result.currencyPerCraft;
        Map<Integer, Integer> total = result.currencyTotal == null ? Map.of() : result.currencyTotal;
        // Union: a currency only later executions consume (first craft used an owned intermediate) still counts.
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>(first.keySet());
        ids.addAll(total.keySet());
        if (ids.isEmpty()) return null;
        List<CurrencyCostsDto.CurrencyCostDto> costs = new ArrayList<>();
        for (int id : ids) {
            int perCraft = first.getOrDefault(id, 0);
            costs.add(new CurrencyCostsDto.CurrencyCostDto(id,
                    CURRENCY_NAMES.getOrDefault(id, "Currency " + id), perCraft, total.getOrDefault(id, 0)));
        }
        return new CurrencyCostsDto(result.walletState, result.unpricedCurrency, result.economicsFromHypotheticalCraft,
                costs, toEstimate(result, items, detail),
                result.unpricedCurrencyIds == null ? List.of() : result.unpricedCurrencyIds.stream().sorted().toList());
    }

    private static CurrencyCostsDto.NoteEstimateDto toEstimate(CraftResult result,
                                                               Map<Integer, ItemRepository.ItemInfo> items,
                                                               boolean detail) {
        craft.ResearchNoteEstimate estimate = result.noteEstimate;
        if (estimate == null || (result.estimatedNotesPerCraft == 0 && result.estimatedNotesTotal == 0)) return null;
        List<CurrencyCostsDto.VariantDto> variants = new ArrayList<>();
        if (detail) {
            for (craft.ResearchNoteEstimate.Variant v : estimate.variants()) {
                List<CurrencyCostsDto.LineDto> lines = new ArrayList<>();
                for (craft.ResearchNoteEstimate.Line line : v.ingredients()) {
                    lines.add(new CurrencyCostsDto.LineDto(line.itemId(), itemName(line.itemId(), items), line.count(),
                            line.unitPriceCopper(), line.totalCopper(), iconUrl(line.itemId(), items)));
                }
                variants.add(new CurrencyCostsDto.VariantDto(v.recipeId(), itemName(v.outputItemId(), items),
                        v.notesPerBatch(), v.batchCostCopper(), per100(v),
                        estimate.basisRecipeId() != null && estimate.basisRecipeId() == v.recipeId(),
                        List.copyOf(lines)));
            }
        }
        return new CurrencyCostsDto.NoteEstimateDto(result.estimatedNotesPerCraft, result.estimatedNotesTotal,
                result.estimatedNotesCostPerCraftCopper, result.estimatedNotesCostTotalCopper,
                estimate.basisRecipeId(), List.copyOf(variants));
    }

    /** Backend-owned display amount: ceil(batchCost * 100 / notesPerBatch), exact; null when unpriced or unrepresentable. */
    private static Long per100(craft.ResearchNoteEstimate.Variant v) {
        if (!v.priced() || v.notesPerBatch() <= 0) return null;
        try {
            java.math.BigInteger[] qr = java.math.BigInteger.valueOf(v.batchCostCopper())
                    .multiply(java.math.BigInteger.valueOf(100))
                    .divideAndRemainder(java.math.BigInteger.valueOf(v.notesPerBatch()));
            java.math.BigInteger up = qr[1].signum() > 0 ? qr[0].add(java.math.BigInteger.ONE) : qr[0];
            return up.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** Sorted by item id purely so the response is stable; the domain's maps are unordered. */
    private static List<MissingItemDto> toMissing(Map<Integer, Integer> missing,
                                                  Map<Integer, MaterialPurchaseCost> purchaseCosts,
                                                  Map<Integer, ItemRepository.ItemInfo> items,
                                                  Map<Integer, PriceQuote> tp) {

        if (missing == null || missing.isEmpty()) return List.of();

        List<MissingItemDto> out = new ArrayList<>(missing.size());
        for (Map.Entry<Integer, Integer> e : missing.entrySet()) {
            MaterialPurchaseCost purchase = purchaseCosts.get(e.getKey());
            out.add(new MissingItemDto(e.getKey(), itemName(e.getKey(), items), e.getValue(),
                    toQuote(tp.get(e.getKey())), iconUrl(e.getKey(), items),
                    purchase == null ? null : purchase.unitPriceCopper(),
                    purchase == null ? null : purchase.totalPriceCopper()));
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

    /** Flat direct-ingredient display/search metadata; no recursive tree or additional lookup. */
    private static List<String> ingredientNames(Recipe recipe, Map<Integer, ItemRepository.ItemInfo> items) {
        return recipe.ingredients.stream()
                .map(ingredient -> itemName(ingredient.itemId, items))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The item's image URL, derived from the retained source the calculation's own batch item read
     * already carried. Null when the item is not in that set or its
     * metadata is absent or unacceptable - no lookup, no request and no calculation happens here.
     */
    private static String iconUrl(int itemId, Map<Integer, ItemRepository.ItemInfo> items) {
        ItemRepository.ItemInfo info = items.get(itemId);
        return info == null ? null : ItemIconUrls.iconUrlFor(itemId, info.iconUrl);
    }
}
