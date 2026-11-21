package craft;

import tradingpost.TradingPostFeePolicy;

import java.util.Map;

public class RecipeSimulator {

    /** UD-003 / DOMAIN_SPEC.md section 28: accepted maximum across all simulation phases. */
    static final int MAX_CRAFTS = 250;

    private final CraftingResolver resolver;

    /**
     * Optional accepted-craft limit used by resolution explanations. Table calculations keep the
     * existing 250-craft behavior; explanation runs can stop at the selected result's counted craft
     * quantity without attempting an additional craft.
     */
    private final int acceptedCraftLimit;
    private final boolean captureAcceptedCrafts;

    public RecipeSimulator() {
        this(new CraftingResolver(), 0, false);
    }

    RecipeSimulator(CraftingResolver resolver, boolean stopAfterFirstCraft) {
        this(resolver, stopAfterFirstCraft ? 1 : 0, stopAfterFirstCraft);
    }

    RecipeSimulator(CraftingResolver resolver, int acceptedCraftLimit, boolean captureAcceptedCrafts) {
        this.resolver = resolver;
        this.acceptedCraftLimit = acceptedCraftLimit;
        this.captureAcceptedCrafts = captureAcceptedCrafts;
    }

    public RecipeSimulationResult simulateRecipe(
            Recipe recipe,
            PlannerContext ctx,
            PlanState baseState
                                                ) {
        return simulateOnState(recipe, ctx, new PlanState(baseState));
    }

    /** Caller owns this state exclusively and restores it after the recipe, including on failure. */
    RecipeSimulationResult simulateOnState(Recipe recipe, PlannerContext ctx, PlanState state) {
        RecipeSimulationResult result = new RecipeSimulationResult(recipe.recipeId, recipe.outputItemId);

        // Phase 1: when inventory use and buying are both enabled, prefer fully-owned paths first.
        if (ctx.settings.useOwnMats && ctx.settings.allowBuying) {
            // Same no-buy settings this phase has always used; withBuyingDisabled() additionally
            // shares ctx's memo tables instead of giving every recipe's phase 1 an empty one.
            simulatePhase(recipe, ctx.withBuyingDisabled(), state, result);
        }

        // Continue with the requested buying policy. With buying disabled, every recursive
        // resolver call sees buying disabled too; daily crafting remains independently controlled.
        simulatePhase(recipe, ctx, state, result);

        result.setCurrencyTotal(state.currencySpentSnapshot());
        return result;
    }

    private void simulatePhase(
            Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            RecipeSimulationResult result
                              ) {
        while (true) {
            if (result.getCraftCount() >= MAX_CRAFTS) {
                break;
            }
            if (acceptedCraftLimit > 0 && result.getCraftCount() >= acceptedCraftLimit) {
                break;
            }

            // Each additional batch is attempted on the live state and undone if it turns out to
            // be unaffordable or unsatisfiable; the accepted batches stay. Previously every one of
            // these up-to-250 iterations copied the entire state before attempting
            // (STORY-PERF-001).
            int mark = state.mark();
            int surplusBefore = state.surplusOf(recipe.outputItemId);
            Map<Integer, Integer> spentBefore = state.currencySpentSnapshot();

            ResolveResult rr = resolver.resolveOneCraft(recipe, ctx, state);
            ResolvedNeed root = rr.getRoot();

            // The ordinary economic route can exceed the cash budget. Retry once minimizing cash at each
            // choice, retaining a mix of affordable purchases and crafts rather than switching every ingredient.
            if (budgetExceeded(ctx, result, rr) && root.getQtySatisfied() >= root.getQtyRequested()
                    && !ctx.minimizeBuyCost) {
                state.rollbackTo(mark);
                ResolveResult retry = resolver.resolveOneCraft(recipe, ctx.withBuyCostMinimized(), state);
                if (retry.getRoot().getQtySatisfied() >= retry.getRoot().getQtyRequested()
                        && !budgetExceeded(ctx, result, retry)) {
                    rr = retry;
                    root = rr.getRoot();
                } else {
                    state.rollbackTo(mark);
                    rr = resolver.resolveOneCraft(recipe, ctx, state);
                    root = rr.getRoot();
                }
            }

            if (root.getQtySatisfied() < root.getQtyRequested()) {
                state.rollbackTo(mark);
                result.setBlockedReason(root.getBlockedReason());
                result.setBlockedAttempt(root);
                break;
            }

            int nextBuyTotal;
            try {
                // Every accumulated total, and each batch's effective cost, must stay representable.
                nextBuyTotal = Math.addExact(result.getBuyCostTotal(), rr.getBuyCostCopper());
                int nextOpportunityTotal = Math.addExact(result.getOpportunityCostTotal(),
                        rr.getOpportunityCostCopper());
                Math.addExact(nextBuyTotal, nextOpportunityTotal);
                rr.getEffectiveCostCopper();
            } catch (ArithmeticException outOfRange) {
                state.rollbackTo(mark);
                result.setBlockedReason(BlockedReason.VALUE_RANGE);
                result.setBlockedAttempt(root);
                break;
            }

            long noteCostTotal;
            try {
                // The estimated wallet-Note value is part of the cumulative profit: rounded up once over the
                // cumulative Note quantity, so a craft that would push it out of range is rejected alone.
                long cumulativeNotes = Math.addExact(result.getWalletNoteUnitsTotal(), (long) root.getWalletNoteUnits());
                ResearchNoteEstimate noteEstimate = ctx.researchNoteEstimate();
                noteCostTotal = cumulativeNotes > 0 && noteEstimate.available()
                        ? noteEstimate.costCopper(cumulativeNotes) : 0;
            } catch (ArithmeticException outOfRange) {
                state.rollbackTo(mark);
                result.setBlockedReason(BlockedReason.VALUE_RANGE);
                result.setBlockedAttempt(root);
                break;
            }

            int prospectiveReused = result.getReusedSeedUnits();
            if (recipe.hasExpectedYield() && recipe.selfSeedUnits() > 0) {
                int consumedNow = Math.max(0, surplusBefore - state.surplusOf(recipe.outputItemId));
                prospectiveReused += Math.min(consumedNow, result.seedCreditOutstanding());
            }
            if (!aggregatesRepresentable(recipe, ctx, result.getCraftCount() + 1, prospectiveReused,
                    nextBuyTotal, Math.addExact(result.getOpportunityCostTotal(), rr.getOpportunityCostCopper())
                            + noteCostTotal)) {
                // Keep the batches already accepted; only this one would push a revenue/profit
                // aggregate outside the supported range.
                state.rollbackTo(mark);
                result.setBlockedReason(BlockedReason.VALUE_RANGE);
                result.setBlockedAttempt(root);
                break;
            }

            if (ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper) {
                state.rollbackTo(mark);
                result.setBlockedReason(BlockedReason.INSUFFICIENT_BUDGET);
                result.setBlockedAttempt(root);
                break;
            }

            result.setBlockedReason(BlockedReason.NONE);
            result.setBlockedAttempt(null);
            // Accepted: this batch is never revisited, so its undo information can be dropped -
            // without this the journal would grow across all 250 iterations.
            state.commitTo(mark);

            // A promotion consuming its own output as a seed: units an earlier batch's guaranteed minimum left in
            // surplus and this batch consumed are reused exactly once (never sold and consumed). Only the guaranteed
            // minimum is credited for the next batch; fractional expected surplus is never guaranteed inventory.
            if (recipe.hasExpectedYield() && recipe.selfSeedUnits() > 0) {
                int consumed = Math.max(0, surplusBefore - state.surplusOf(recipe.outputItemId));
                int reused = Math.min(consumed, result.seedCreditOutstanding());
                result.addReusedSeedUnits(reused);
                int credit = result.seedCreditOutstanding() - reused + recipe.expectedYield.min();
                result.setSeedCreditOutstanding(credit);
                state.addSurplus(recipe.outputItemId, recipe.expectedYield.min());
            }

            if (result.getCraftCount() == 0) {
                result.setFirstCraft(root);
                result.setCurrencyFirstCraft(difference(state.currencySpentSnapshot(), spentBefore));
            }

            if (root.usesExpectedYield()) result.markUsesExpectedYield();
            if (captureAcceptedCrafts) result.addAcceptedCraft(root);

            result.setLastCraft(root);
            result.incrementCraftCount();
            result.addBuyCost(rr.getBuyCostCopper());
            result.addOpportunityCost(rr.getOpportunityCostCopper());
            if (rr.hasUnvaluedOwnedInput()) result.markUnvaluedOwnedInput();
            if (root.spendsUnpricedCurrency()) {
                result.markUnpricedCurrency();
                result.markUnpricedCurrencies(root.unpricedCurrencyIds());
            }
            result.addWalletNoteUnits(root.getWalletNoteUnits());

            root.accumulateBoughtItems(result.getTotalMissingToBuy());
            root.accumulateVendorPurchases(result.getTotalVendorPurchases());

            if (result.getCraftCount() >= MAX_CRAFTS) {
                break;
            }
        }
    }

    private static boolean budgetExceeded(PlannerContext ctx, RecipeSimulationResult result, ResolveResult rr) {
        return ctx.settings.maxBuyCopper > 0
                && (long) result.getBuyCostTotal() + rr.getBuyCostCopper() > ctx.settings.maxBuyCopper;
    }

    /**
     * Whether the revenue and profit totals for {@code crafts} batches stay inside the int range
     * the result reports, evaluated exactly in long arithmetic. Reject an unrepresentable per-craft
     * revenue here too, so standalone explanations and planner rows report the same VALUE_RANGE.
     */
    private static boolean aggregatesRepresentable(Recipe recipe, PlannerContext ctx, int crafts, int reusedSeed,
                                                   long buyTotal, long opportunityTotal) {
        int revenueOne;
        try {
            revenueOne = CostEvaluator.computeRevenue(recipe, ctx.tp, ctx.settings);
        } catch (ArithmeticException outOfRange) {
            return false;
        }
        long sellTotal;
        long feeTotal;
        if (recipe.hasExpectedYield()) {
            // The exact aggregate the planner reports: one floor over (crafts x mean - reused seed), one fee.
            try {
                sellTotal = expectedSellTotal(recipe, ctx, crafts, reusedSeed);
            } catch (ArithmeticException outOfRange) {
                return false;
            }
            feeTotal = fits(sellTotal) && sellTotal > 0 ? TradingPostFeePolicy.feeOn(sellTotal) : 0;
        } else {
            long feeOne = revenueOne > 0 ? TradingPostFeePolicy.feeOn(revenueOne) : 0;
            sellTotal = (long) revenueOne * crafts;
            feeTotal = feeOne * crafts;
        }
        long profit = sellTotal - feeTotal - buyTotal - opportunityTotal;
        return fits(sellTotal) && fits(profit);
    }

    /** Exact gross expected sell value (copper) for {@code crafts} batches less reused seed units; floored once. */
    static long expectedSellTotal(Recipe recipe, PlannerContext ctx, int crafts, int reusedSeed) {
        Integer unit = CostEvaluator.outputUnit(recipe, ctx.tp, ctx.settings);
        if (unit == null) return 0;
        java.math.BigDecimal units = recipe.expectedYield.mean().multiply(java.math.BigDecimal.valueOf(crafts))
                .subtract(java.math.BigDecimal.valueOf(reusedSeed));
        return ExpectedYield.floorCopper(units, unit);
    }

    private static Map<Integer, Integer> difference(Map<Integer, Integer> after, Map<Integer, Integer> before) {
        if (after.isEmpty()) return Map.of();
        Map<Integer, Integer> delta = new java.util.TreeMap<>();
        after.forEach((id, spent) -> {
            int d = spent - before.getOrDefault(id, 0);
            if (d > 0) delta.put(id, d);
        });
        return Map.copyOf(delta);
    }

    private static boolean fits(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

}
