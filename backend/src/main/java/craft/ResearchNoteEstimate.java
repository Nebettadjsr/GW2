package craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Approved, deliberately small exception to deferred currency valuation (DOMAIN_SPEC.md 2.1.0): the replacement
 * cost of one Research Note, estimated from the actual Peer Reviewed Research recipes (15120 and 15131) priced
 * with the captured Trading Post quotes and the selected buy-price mode. Immutable; computed once per captured
 * calculation and shared. Owned materials are never free here and no other currency is touched.
 *
 * @param variants       both actual recipes in recipe-id order, each priced or marked unavailable
 * @param basisRecipeId  the cheaper fully priced recipe, or null when neither can be completely priced
 */
public record ResearchNoteEstimate(List<Variant> variants, Integer basisRecipeId) {

    public static final List<Integer> VARIANT_RECIPE_IDS = List.of(15120, 15131);

    /** @param unitPriceCopper null when no usable quote exists; never zero-filled */
    public record Line(int itemId, int count, Integer unitPriceCopper) {
        public Long totalCopper() {
            return unitPriceCopper == null ? null : (long) unitPriceCopper * count;
        }
    }

    /** @param batchCostCopper null when the recipe cannot be completely priced */
    public record Variant(int recipeId, int outputItemId, int outputCount, int notesPerBatch,
                          List<Line> ingredients, Long batchCostCopper) {
        public boolean priced() { return batchCostCopper != null; }
    }

    public static ResearchNoteEstimate unavailable() {
        return new ResearchNoteEstimate(List.of(), null);
    }

    public static ResearchNoteEstimate compute(Map<Integer, List<Recipe>> recipesByOutput,
                                               Map<Integer, PriceQuote> tp, CraftingSettings settings) {
        CurrencyProduction production = CurrencyProduction.forCurrency(CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID)
                .orElseThrow();
        List<Recipe> producers = recipesByOutput.getOrDefault(production.itemId(), List.of());
        List<Variant> variants = new ArrayList<>();
        for (int recipeId : VARIANT_RECIPE_IDS) {
            Recipe recipe = producers.stream().filter(r -> r.recipeId == recipeId).findFirst().orElse(null);
            if (recipe == null) continue;
            variants.add(price(recipe, production, tp, settings));
        }
        Variant best = null;
        for (Variant v : variants) {
            if (v.priced() && (best == null || perNoteLess(v, best))) best = v;
        }
        return new ResearchNoteEstimate(List.copyOf(variants), best == null ? null : best.recipeId());
    }

    private static boolean perNoteLess(Variant a, Variant b) {
        // a.cost / a.notes < b.cost / b.notes, cross-multiplied (no division, no rounding).
        return Math.multiplyExact(a.batchCostCopper(), (long) b.notesPerBatch())
                < Math.multiplyExact(b.batchCostCopper(), (long) a.notesPerBatch());
    }

    private static Variant price(Recipe recipe, CurrencyProduction production,
                                 Map<Integer, PriceQuote> tp, CraftingSettings settings) {
        List<Line> lines = new ArrayList<>();
        boolean complete = recipe.currencies.isEmpty() && recipe.outputCount > 0 && !recipe.ingredients.isEmpty();
        long total = 0;
        for (Ingredient ing : recipe.ingredients) {
            PriceQuote quote = tp.get(ing.itemId);
            Integer unit = quote == null ? null : (settings.listingBuy ? quote.buyUnit : quote.sellUnit);
            if (unit != null && unit <= 0) unit = null;
            lines.add(new Line(ing.itemId, ing.count, unit));
            if (unit == null || ing.count <= 0) {
                complete = false;
            } else {
                total += (long) unit * ing.count;
                if (total > Integer.MAX_VALUE) complete = false;
            }
        }
        long exactNotes = recipe.outputCount > 0 ? (long) production.yield() * recipe.outputCount : production.yield();
        if (exactNotes <= 0 || exactNotes > Integer.MAX_VALUE) complete = false;
        int notes = exactNotes <= 0 || exactNotes > Integer.MAX_VALUE ? Math.max(1, production.yield()) : (int) exactNotes;
        return new Variant(recipe.recipeId, recipe.outputItemId, recipe.outputCount, notes, List.copyOf(lines),
                complete ? total : null);
    }

    public boolean available() {
        return basisRecipeId != null;
    }

    public Variant basis() {
        if (basisRecipeId == null) return null;
        return variants.stream().filter(v -> v.recipeId() == basisRecipeId).findFirst().orElse(null);
    }

    /**
     * Whole copper for {@code notes} Notes at the fractional per-Note rate: the aggregate is rounded up once,
     * never per Note. Throws {@link ArithmeticException} when out of the int range.
     */
    public int costCopper(long notes) {
        return Math.toIntExact(costCopperExact(notes));
    }

    /**
     * Same checked ratio/ceil as {@link #costCopper(long)} but carried as a long; throws {@link ArithmeticException}
     * only beyond the exact JSON integer range (2^53-1) used by the Forge.
     */
    public long costCopperExact(long notes) {
        if (notes <= 0) return 0;
        Variant basis = basis();
        if (basis == null) throw new IllegalStateException("no usable Research Note estimate");
        long numerator = Math.multiplyExact(notes, basis.batchCostCopper());
        long copper = Math.addExact(numerator, basis.notesPerBatch() - 1L) / basis.notesPerBatch();
        if (copper > 9_007_199_254_740_991L) throw new ArithmeticException("beyond the exact JSON integer range");
        return copper;
    }
}
