package craft;

import tradingpost.TradingPostFeePolicy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Recursive planner:
 * - Inventory first
 * - If missing ingredient has a recipe -> craft it (recursively)
 * - Otherwise -> buy it (if allowed) or mark missing (if not allowed)
 */
public class CraftingPlanner {

    private final CostEvaluator costEvaluator = new CostEvaluator();
    private final Map<Integer, Integer> vendorValues;
    private final boolean reuseSimulationState;
    /** Account wallet by currency id; {@code null} = unknown (never zero or unlimited). */
    private final Map<Integer, Integer> wallet;

    public CraftingPlanner() {
        this(Map.of(), true);
    }

    CraftingPlanner(boolean reuseSimulationState) {
        this(Map.of(), reuseSimulationState);
    }

    private CraftingPlanner(Map<Integer, Integer> vendorValues, boolean reuseSimulationState) {
        this(vendorValues, reuseSimulationState, null);
    }

    private CraftingPlanner(Map<Integer, Integer> vendorValues, boolean reuseSimulationState,
                            Map<Integer, Integer> wallet) {
        this.wallet = wallet;
        this.vendorValues = vendorValues;
        this.reuseSimulationState = reuseSimulationState;
    }

    /**
     * A planner that values untradeable owned materials with these vendor sell values
     * (DOMAIN_SPEC.md 11.2 step 2), keyed by item id; non-positive values mean "no vendor value".
     */
    public CraftingPlanner withVendorValues(Map<Integer, Integer> vendorValues) {
        return vendorValues.equals(this.vendorValues) ? this : new CraftingPlanner(Map.copyOf(vendorValues), reuseSimulationState, wallet);
    }

    /** A planner spending from this account wallet (currency id to balance); {@code null} means unknown. */
    public CraftingPlanner withWallet(Map<Integer, Integer> wallet) {
        // Unchanged wallet (typically still unknown) keeps this very planner, like withVendorValues does.
        if (java.util.Objects.equals(wallet, this.wallet)) return this;
        return new CraftingPlanner(vendorValues, reuseSimulationState, wallet == null ? null : Map.copyOf(wallet));
    }

    public Map<Integer, CraftResult> evaluateAll(List<Recipe> recipes,
                                                 Map<Integer, Integer> inventory,
                                                 Map<Integer, PriceQuote> tp,
                                                 CraftingSettings settings,
                                                 Set<Integer> allowedRecipeIds) {
        return evaluateAll(recipes, inventory, Map.of(), tp, settings, allowedRecipeIds);
    }

    /** Evaluates exactly one discovery attempt for each recipe, with no cumulative buy budget. */
    public Map<Integer, CraftResult> evaluateAllSingleCraft(List<Recipe> recipes,
                                                            Map<Integer, Integer> sellableInventory,
                                                            Map<Integer, Integer> boundInventory,
                                                            Map<Integer, PriceQuote> tp,
                                                            CraftingSettings settings,
                                                            Set<Integer> allowedRecipeIds) {
        Map<Integer, List<Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds).withVendorValues(vendorValues).withWallet(wallet);
        return recipes.parallelStream().collect(Collectors.toConcurrentMap(
                r -> r.recipeId,
                r -> evaluateOneRecipeNew(r, new PlanState(sellableInventory, boundInventory), ctx,
                        new RecipeSimulator(new CraftingResolver(), 1, true)),
                (a, b) -> b));
    }

    /**
     * @param sellableInventory ordinary unbound/tradable owned quantity, priced exactly as
     *                          {@link #evaluateAll(List, Map, Map, CraftingSettings, Set)} always has.
     * @param boundInventory    account-bound/soulbound-and-usable-by-the-selected-character owned
     *                          quantity (DOMAIN_SPEC.md section 11.1 / DQ-007); consumed first and
     *                          never charged Trading-Post opportunity cost. Empty for callers that
     *                          don't have a selected character, so behavior is unchanged for them.
     */
    public Map<Integer, CraftResult> evaluateAll(List<Recipe> recipes,
                                                 Map<Integer, Integer> sellableInventory,
                                                 Map<Integer, Integer> boundInventory,
                                                 Map<Integer, PriceQuote> tp,
                                                 CraftingSettings settings,
                                                 Set<Integer> allowedRecipeIds) {

        Map<Integer, List<Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds).withVendorValues(vendorValues).withWallet(wallet);

        // Every recipe's simulation only reads shared/immutable inputs (ctx, sellableInventory,
        // boundInventory) and writes to its own fresh PlanState/RecipeSimulator, so recipes are
        // evaluated independently of one another; the profiled bottleneck (STORY-PERF-001) is
        // this per-recipe simulation cost, not I/O, so distributing it across cores is a bounded,
        // result-preserving optimization rather than an assumed one.
        return recipes.parallelStream().collect(Collectors.toConcurrentMap(
                r -> r.recipeId,
                r -> evaluateOneRecipeNew(r, new PlanState(sellableInventory, boundInventory), ctx, new RecipeSimulator()),
                (a, b) -> b
        ));
    }

    /**
     * Coordinated multi-character planning (DOMAIN_SPEC.md section 2.2.1 / STORY-DOM-014): unlike
     * {@link #evaluateAll(List, Map, Map, Map, CraftingSettings, Set)}, {@code roster} identifies
     * every candidate character and their crafting ratings, so each recipe/sub-recipe execution
     * may be assigned to whichever eligible candidate can perform it, and {@code
     * characterBoundInventory} keeps each character's soulbound owned quantity usable only by a
     * step assigned to that exact character. {@code accountBoundInventory} (account-bound, no TP
     * opportunity cost) and {@code sellableInventory} (ordinary tradable) remain shared account-
     * wide pools, exactly as the legacy overload's single {@code boundInventory} always was.
     */
    public Map<Integer, CraftResult> evaluateAllCoordinated(
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds) {

        Map<Integer, List<Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                roster).withVendorValues(vendorValues).withWallet(wallet);

        // Request-local: no account state remains attached to shared pool threads after return.
        Map<Thread, PlanState> workerStates = new java.util.concurrent.ConcurrentHashMap<>();
        return recipes.parallelStream().collect(Collectors.toConcurrentMap(
                r -> r.recipeId,
                r -> {
                    PlanState state = reuseSimulationState
                            ? workerStates.computeIfAbsent(Thread.currentThread(), ignored ->
                                PlanState.reusable(sellableInventory, accountBoundInventory, characterBoundInventory))
                            : new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory);
                    try {
                        return evaluateOneRecipeNew(r, state, ctx, new RecipeSimulator());
                    } finally {
                        if (reuseSimulationState) state.rollbackTo(0);
                    }
                }
        ));
    }

    /**
     * The result for exactly one recipe, computed the way
     * {@link #evaluateAll(List, Map, Map, Map, CraftingSettings, Set)} computes that recipe's
     * entry: the same context, the same fresh {@link PlanState} built from the supplied initial
     * inventory, and the same per-recipe evaluation - including its heuristic skip. Provided for
     * the selected-recipe detail operation (ARCHITECTURE.md section 7), which needs one
     * row rather than a whole table; it is an entry point, not a second set of rules.
     */
    public CraftResult evaluateOne(Recipe recipe,
                                   List<Recipe> recipes,
                                   Map<Integer, Integer> sellableInventory,
                                   Map<Integer, Integer> boundInventory,
                                   Map<Integer, PriceQuote> tp,
                                   CraftingSettings settings,
                                   Set<Integer> allowedRecipeIds) {

        PlannerContext ctx = new PlannerContext(
                buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds).withVendorValues(vendorValues).withWallet(wallet);

        return evaluateOneRecipeNew(
                recipe, new PlanState(sellableInventory, boundInventory), ctx, new RecipeSimulator());
    }

    /** Resolves the row values for one Discovery craft, regardless of available inventory. */
    public CraftResult evaluateSingleCraft(Recipe recipe,
                                           List<Recipe> recipes,
                                           Map<Integer, Integer> sellableInventory,
                                           Map<Integer, Integer> boundInventory,
                                           Map<Integer, PriceQuote> tp,
                                           CraftingSettings settings,
                                           Set<Integer> allowedRecipeIds) {
        PlannerContext ctx = new PlannerContext(
                buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds).withVendorValues(vendorValues).withWallet(wallet);
        return evaluateOneRecipeNew(recipe, new PlanState(sellableInventory, boundInventory), ctx,
                new RecipeSimulator(new CraftingResolver(), 1, true));
    }

    /**
     * As {@link #evaluateOne(Recipe, List, Map, Map, Map, CraftingSettings, Set)}, but on the
     * coordinated-roster path, computing exactly that recipe's entry of
     * {@link #evaluateAllCoordinated(List, Map, Map, Map, List, Map, CraftingSettings, Set)}.
     */
    public CraftResult evaluateOneCoordinated(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds) {

        PlannerContext ctx = new PlannerContext(
                buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds, roster).withVendorValues(vendorValues).withWallet(wallet);

        return evaluateOneRecipeNew(
                recipe,
                new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory),
                ctx,
                new RecipeSimulator());
    }

    static Map<Integer, List<Recipe>> buildRecipesByOutput(List<Recipe> recipes) {
        Map<Integer, List<Recipe>> recipesByOutput = new HashMap<>();
        for (Recipe r : recipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }
        return recipesByOutput;
    }

    private CraftResult evaluateOneRecipeNew(
            Recipe recipe,
            PlanState baseState,
            PlannerContext ctx,
            RecipeSimulator recipeSimulator
    ) {
        try {
            return evaluateOneRecipeChecked(recipe, baseState, ctx, recipeSimulator);
        } catch (ArithmeticException outOfRange) {
            // A quantity, price, fee or profit left the supported range: the recipe is unavailable
            // with an explicit reason, never a wrapped, clamped or fabricated figure.
            return new CraftResult(recipe.outputItemId, recipe.disciplinesText, 0, Map.of(), Map.of(),
                    0, 0, 0, 0, 0, 0, null, BlockedReason.VALUE_RANGE, 0, Map.of(), Map.of());
        }
    }

    private CraftResult evaluateOneRecipeChecked(
            Recipe recipe,
            PlanState baseState,
            PlannerContext ctx,
            RecipeSimulator recipeSimulator
    ) {
        RecipeSimulationResult sim;

        // A disabled inventory or buying source does not disable recursive crafting. In
        // particular, direct-price profitability estimates cannot safely prove a recipe
        // uncraftable when its ingredients may themselves be crafted.
        sim = baseState.isReusable() ? recipeSimulator.simulateOnState(recipe, ctx, baseState)
                : recipeSimulator.simulateRecipe(recipe, ctx, baseState);

        // A wallet-blocked currency recipe keeps valid output quotes and material costs: its single-craft
        // economics come from a hypothetical craft that tallies currency without being limited by it.
        boolean hypothetical = false;
        if (sim.getCraftCount() == 0 && isWalletBlock(sim.getBlockedReason())) {
            RecipeSimulationResult what = new RecipeSimulator(new CraftingResolver(), 1, true)
                    .simulateRecipe(recipe, ctx.withWalletIgnored(), new PlanState(baseState));
            if (what.getCraftCount() > 0) {
                what.setBlockedReason(sim.getBlockedReason());
                sim = what;
                hypothetical = true;
            }
        }
        CostEvaluationResult cost = costEvaluator.evaluate(recipe, sim, ctx.tp, ctx.settings);

        ResolvedNeed firstCraft = sim.getFirstCraft();
        Node tree = null;

        Map<Integer, Integer> missingToBuyOne = new HashMap<>();
        if (firstCraft != null) {
            firstCraft.accumulateBoughtItems(missingToBuyOne);
        }

        Map<Integer, MaterialPurchaseCost> purchaseCosts = purchaseCosts(
                sim.getTotalMissingToBuy(), ctx.tp, ctx.settings);
        int totalBuyCost = sim.getBuyCostTotal();
        Map<Integer, MaterialPurchaseCost> purchaseCostsOne = purchaseCosts(missingToBuyOne, ctx.tp, ctx.settings);
        int buyCostOne = firstCraft == null ? 0 : firstCraft.getBuyCostCopper();

        int revenueOne = cost.getRevenuePerCraft();
        int matsSellOne = cost.getOpportunityCostPerCraft();

        // DOMAIN_SPEC.md §25/§26 (resolved UD-011): profit is the gross output sell value less the
        // decided 15% Trading Post fee, purchased-material cost and owned-material opportunity cost.
        // The fee is charged here, once, and only against the profit: revenueOne stays the gross
        // figure §24 defines, which is what the totals, the market displays and the availability
        // predicates below and downstream go on reading. The fee never exceeds the revenue it is
        // taken from, so it still fits an int.
        //
        // A revenue of zero or less is not a sale and carries no fee: that is the value an
        // unavailable or non-positive output quote already reports (DOMAIN_SPEC.md §21), and the
        // downstream availability predicates still see exactly the figure they saw before.
        int feeOne = revenueOne > 0 ? (int) TradingPostFeePolicy.feeOn(revenueOne) : 0;
        // Approved exception (DOMAIN_SPEC.md 2.1.0): Notes paid from the existing wallet are valued at the
        // estimated replacement rate, aggregated and rounded up once. Produced Notes are already charged in buyCostOne.
        ResearchNoteEstimate noteEstimate = ctx.researchNoteEstimate();
        boolean noteValued = noteEstimate.available();
        int notesOne = noteValued && firstCraft != null ? firstCraft.getWalletNoteUnits() : 0;
        long notesTotal = noteValued && !hypothetical ? sim.getWalletNoteUnitsTotal() : 0;
        int noteCostOne = notesOne > 0 ? noteEstimate.costCopper(notesOne) : 0;
        int noteCostTotal = notesTotal > 0 ? noteEstimate.costCopper(notesTotal) : 0;
        int profitOne = Math.toIntExact((long) revenueOne - feeOne - buyCostOne - matsSellOne - noteCostOne);

        // Owned-material use can vary by execution, so the total uses full-plan aggregates below.

        // DOMAIN_SPEC.md §2.1.1: §25's per-execution output revenue - which already carries the
        // recipe's output quantity and deducts no selling fee - for §28's craftable count. Built
        // from the two figures above rather than from a second resolver or price read.
        int realCrafts = hypothetical ? 0 : sim.getCraftCount();
        int totalSellValue = Math.multiplyExact(revenueOne, realCrafts);
        // Inventory consumed can vary between executions, so total profit uses costs from the
        // accepted full plan instead of scaling the first craft's opportunity cost.
        int totalProfit = Math.toIntExact((long) totalSellValue
                - (long) feeOne * realCrafts
                - (hypothetical ? 0 : sim.getBuyCostTotal()) - (hypothetical ? 0 : sim.getOpportunityCostTotal())
                - noteCostTotal);

        CraftResult result = new CraftResult(
                recipe.outputItemId,
                recipe.disciplinesText,
                realCrafts,
                hypothetical ? Map.of() : sim.getTotalMissingToBuy(),
                missingToBuyOne,
                hypothetical ? 0 : totalBuyCost,
                matsSellOne,
                revenueOne,
                profitOne,
                totalProfit,
                totalSellValue,
                tree,
                sim.getBlockedReason(),
                hypothetical ? 0 : sim.getOpportunityCostTotal(),
                hypothetical ? Map.of() : purchaseCosts,
                purchaseCostsOne
        );
        result.unvaluedOwnedInput = sim.hasUnvaluedOwnedInput();
        result.economicsFromHypotheticalCraft = hypothetical;
        Map<Integer, Integer> perCraft = sim.getCurrencyFirstCraft();
        if (perCraft.isEmpty() && !recipe.currencies.isEmpty()) {
            Map<Integer, Integer> direct = new java.util.TreeMap<>();
            recipe.currencies.forEach(c -> direct.merge(c.currencyId(), c.count(), Integer::sum));
            perCraft = direct;
        }
        result.currencyPerCraft = Map.copyOf(perCraft);
        result.currencyTotal = hypothetical ? Map.of() : sim.getCurrencyTotal();
        if (!result.currencyPerCraft.isEmpty() || !result.currencyTotal.isEmpty()) {
            result.walletState = ctx.wallet == null ? "UNKNOWN" : "KNOWN";
            result.unpricedCurrency = sim.spendsUnpricedCurrency() || firstCraft == null;
            java.util.Set<Integer> unpriced = new java.util.TreeSet<>(sim.unpricedCurrencyIds());
            // No real craft: every consumed currency is hypothetical and stays unpriced.
            if (firstCraft == null) {
                unpriced.addAll(result.currencyPerCraft.keySet());
                unpriced.addAll(result.currencyTotal.keySet());
            }
            result.unpricedCurrencyIds = java.util.Set.copyOf(unpriced);
            if (notesOne > 0 || notesTotal > 0) {
                result.estimatedNotesPerCraft = notesOne;
                result.estimatedNotesTotal = Math.toIntExact(notesTotal);
                result.estimatedNotesCostPerCraftCopper = noteCostOne;
                result.estimatedNotesCostTotalCopper = noteCostTotal;
                result.noteEstimate = noteEstimate;
            }
        }
        return result;
    }

    private static boolean isWalletBlock(BlockedReason reason) {
        return reason == BlockedReason.WALLET_UNAVAILABLE || reason == BlockedReason.INSUFFICIENT_CURRENCY;
    }

    private Map<Integer, MaterialPurchaseCost> purchaseCosts(Map<Integer, Integer> missing,
                                                              Map<Integer, PriceQuote> tp,
                                                              CraftingSettings settings) {
        Map<Integer, MaterialPurchaseCost> costs = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : missing.entrySet()) {
            PriceQuote quote = tp.get(entry.getKey());
            Integer unit = quote == null ? null : settings.listingBuy ? quote.buyUnit : quote.sellUnit;
            Integer total = unit == null ? null : Math.multiplyExact(unit, entry.getValue());
            costs.put(entry.getKey(), new MaterialPurchaseCost(unit, total));
        }
        return Map.copyOf(costs);
    }

}
