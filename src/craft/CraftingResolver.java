package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CraftingResolver {

    private final Map<Integer, Integer> directBuyUnitCache = new HashMap<>();
    private final Map<Integer, Integer> directSellUnitCache = new HashMap<>();
    private final Map<Integer, RecipeRepository.Recipe> firstRecipeCache = new HashMap<>();

    public ResolveResult resolveOneCraft(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState state
                                        ) {
        ResolvedNeed root = resolveNeed(recipe.outputItemId, recipe.outputCount, ctx, state, false);
        return new ResolveResult(root);
    }

    public ResolvedNeed resolveNeed(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            boolean allowDirectBuy
                                   ) {
        ResolvedNeed result = new ResolvedNeed(itemId, qtyRequested);

        int remaining = qtyRequested;

        // 1) Use owned inventory first, if enabled
        if (ctx.settings.useOwnMats) {
            int usedFromInventory = state.consumeInventory(itemId, remaining);
            result.setQtyFromInventory(usedFromInventory);

            int oppCost = usedFromInventory * resolveDirectSellUnit(itemId, ctx.tp, ctx.settings);
            result.setOpportunityCostCopper(oppCost);

            remaining -= usedFromInventory;
        }

        // fully satisfied by inventory
        if (remaining <= 0) {
            result.determineMode();
            return result;
        }

        // 2) Evaluate direct buy on a COPY of state
        CandidateEval buyEval = null;
        if (ctx.settings.allowBuying && allowDirectBuy) {
            int buyUnit = resolveDirectBuyUnit(itemId, ctx.tp, ctx.settings);
            if (buyUnit > 0) {
                ResolvedNeed buyNeed = new ResolvedNeed(itemId, remaining);
                buyNeed.setQtyBought(remaining);
                buyNeed.setBuyCostCopper(remaining * buyUnit);
                buyNeed.determineMode();

                Map<Integer, Integer> extraMissing = new HashMap<>();
                extraMissing.put(itemId, remaining);

                buyEval = new CandidateEval(
                        buyNeed,
                        buyNeed.getBuyCostCopper(),
                        extraMissing
                );
            }
        }

        // 3) Evaluate craft on a COPY of state
        CandidateEval craftEval = null;
        RecipeRepository.Recipe firstRecipe = firstRecipeFor(itemId, ctx);

        boolean shouldTryCraft = true;

        if (buyEval != null && firstRecipe != null) {
            int estimatedCraftFloor = estimateDirectCraftFloor(firstRecipe, ctx);

            // normalize to requested quantity
            int times = ceilDiv(remaining, firstRecipe.outputCount);
            long estimatedTotalCraftFloor = (long) estimatedCraftFloor * times;

            if (estimatedTotalCraftFloor >= buyEval.need.getBuyCostCopper()) {
                shouldTryCraft = false;
            }
        }

        if (shouldTryCraft) {
            PlanState craftState = new PlanState(state);
            ResolvedNeed craftNeed = tryCraft(itemId, remaining, ctx, craftState);
            if (craftNeed != null) {
                craftEval = new CandidateEval(craftNeed, craftState);
            }
        }

        // 4) Choose better candidate
        CandidateEval chosen = chooseBetterCandidate(buyEval, craftEval);

        if (chosen != null) {
            if (chosen.isStateCandidate()) {
                assert chosen.stateAfter != null;
                state.copyFrom(chosen.stateAfter);
            }else {
                state.buyCostCopper += chosen.extraBuyCost;

                for (var e : chosen.extraMissing.entrySet()) {
                    state.missingToBuy.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }

            ResolvedNeed chosenNeed = chosen.need;

            result.setQtyCrafted(chosenNeed.getQtyCrafted());
            result.setQtyBought(chosenNeed.getQtyBought());
            result.setQtyBlocked(chosenNeed.getQtyBlocked());
            result.setBuyCostCopper(result.getBuyCostCopper() + chosenNeed.getBuyCostCopper());
            result.setOpportunityCostCopper(result.getOpportunityCostCopper() + chosenNeed.getOpportunityCostCopper());

            if (chosenNeed.getBlockedReason() != BlockedReason.NONE) {
                result.setBlockedReason(chosenNeed.getBlockedReason());
            }

            for (ResolvedNeed child : chosenNeed.getChildren()) {
                result.addChild(child);
            }
        } else {
            result.setQtyBlocked(remaining);

            if (!ctx.settings.allowBuying) {
                result.setBlockedReason(BlockedReason.BUYING_DISABLED);
            } else {
                result.setBlockedReason(BlockedReason.NO_RECIPE);
            }
        }

        result.determineMode();
        return result;
    }

    private ResolvedNeed tryCraft(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state
                                 ) {
        RecipeRepository.Recipe recipe = firstRecipeFor(itemId, ctx);
        if (recipe == null) {
            return null;
        }

        // cycle protection
        if (state.visiting.contains(itemId)) {
            ResolvedNeed blocked = new ResolvedNeed(itemId, qtyRequested);
            blocked.setQtyBlocked(qtyRequested);
            blocked.setBlockedReason(BlockedReason.CYCLE_DETECTED);
            blocked.determineMode();
            return blocked;
        }

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        // Daily mode = BUY -> this node may not be crafted directly
        if (isDaily && ctx.settings.dailyBuyInsteadOfCraft) {
            ResolvedNeed blocked = new ResolvedNeed(itemId, qtyRequested);
            blocked.setQtyBlocked(qtyRequested);
            blocked.setBlockedReason(BlockedReason.DAILY_LIMIT);
            blocked.determineMode();
            return blocked;
        }

        state.visiting.add(itemId);

        try {
            ResolvedNeed craftResult = new ResolvedNeed(itemId, qtyRequested);

            int timesNeeded = ceilDiv(qtyRequested, recipe.outputCount);
            int times = timesNeeded;

            // Daily mode = CRAFT -> at most one craft operation
            if (isDaily) {
                int left = state.dailyLeft.getOrDefault(itemId, 1);
                times = Math.min(timesNeeded, left);

                if (times <= 0) {
                    craftResult.setQtyBlocked(qtyRequested);
                    craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
                    craftResult.determineMode();
                    return craftResult;
                }

                state.dailyLeft.put(itemId, left - times);
            }

            int produced = times * recipe.outputCount;
            int qtySatisfied = Math.min(produced, qtyRequested);

            boolean allChildrenSatisfied = true;

            for (RecipeRepository.Ingredient ing : recipe.ingredients) {
                int childQtyNeeded = ing.count * times;

                ResolvedNeed child = resolveNeed(ing.itemId, childQtyNeeded, ctx, state, true);
                craftResult.addChild(child);
                craftResult.addCostsFromChild(child);

                if (!child.isFullySatisfied()) {
                    allChildrenSatisfied = false;
                }
            }

            if (ctx.settings.dailyBuyInsteadOfCraft && containsAnyDailyItem(craftResult)) {
                craftResult.setQtyBlocked(qtyRequested);
                craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
                craftResult.determineMode();
                return craftResult;
            }

            if (!allChildrenSatisfied) {
                craftResult.setQtyBlocked(qtyRequested);
                craftResult.setBlockedReason(BlockedReason.NO_RECIPE);
                craftResult.determineMode();
                return craftResult;
            }

            craftResult.setQtyCrafted(qtySatisfied);

            if (qtySatisfied < qtyRequested) {
                craftResult.setQtyBlocked(qtyRequested - qtySatisfied);
                craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
            }

            craftResult.determineMode();
            return craftResult;

        } finally {
            state.visiting.remove(itemId);
        }
    }

    private CandidateEval chooseBetterCandidate(CandidateEval buyEval, CandidateEval craftEval) {
        boolean buyValid = buyEval != null
                && buyEval.need != null
                && buyEval.need.getQtyBlocked() == 0;

        boolean craftValid = craftEval != null
                && craftEval.need != null
                && craftEval.need.getQtyBlocked() == 0;

        if (buyValid && craftValid) {
            int buyCash = buyEval.need.getBuyCostCopper();
            int craftCash = craftEval.need.getBuyCostCopper();

            // Prefer the path that needs less real spending
            if (craftCash < buyCash) return craftEval;
            if (buyCash < craftCash) return buyEval;

            // If real cash is equal, use economic comparison
            return craftEval.need.getEffectiveCostCopper() <= buyEval.need.getEffectiveCostCopper()
                   ? craftEval
                   : buyEval;
        }

        if (craftValid) return craftEval;
        if (buyValid) return buyEval;

        return null;
    }

    private int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    public int resolveDirectBuyUnit(
            int itemId,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings
                                   ) {

        Integer cached = directBuyUnitCache.get(itemId);
        if (cached != null) return cached;

        TpPriceRepository.TpQuote q = tp.get(itemId);
        if (q == null) {
            directBuyUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingBuy ? q.buyUnit : q.sellUnit;
        int result = v == null ? 0 : v;

        directBuyUnitCache.put(itemId, result);
        return result;
    }

    public int resolveDirectSellUnit(
            int itemId,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings
                                    ) {

        Integer cached = directSellUnitCache.get(itemId);
        if (cached != null) return cached;

        TpPriceRepository.TpQuote q = tp.get(itemId);
        if (q == null) {
            directSellUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingSell ? q.sellUnit : q.buyUnit;
        int result = v == null ? 0 : v;

        directSellUnitCache.put(itemId, result);
        return result;
    }

    private boolean containsAnyDailyItem(ResolvedNeed need) {
        if (need == null) return false;

        if (DailyCrafts.isDailyOutput(need.getItemId())) {
            return true;
        }

        for (ResolvedNeed child : need.getChildren()) {
            if (containsAnyDailyItem(child)) {
                return true;
            }
        }

        return false;
    }

    public RecipeRepository.Recipe firstRecipeFor(int itemId, PlannerContext ctx) {

        RecipeRepository.Recipe cached = firstRecipeCache.get(itemId);
        if (cached != null) return cached;

        List<RecipeRepository.Recipe> list = ctx.recipesByOutput.get(itemId);

        RecipeRepository.Recipe result = (list == null || list.isEmpty()) ? null : list.get(0);

        firstRecipeCache.put(itemId, result);

        return result;
    }

    private static class CandidateEval {
        final ResolvedNeed need;
        final PlanState stateAfter;
        final int extraBuyCost;
        final Map<Integer, Integer> extraMissing;

        CandidateEval(ResolvedNeed need, PlanState stateAfter) {
            this.need = need;
            this.stateAfter = stateAfter;
            this.extraBuyCost = 0;
            this.extraMissing = Map.of();
        }

        CandidateEval(ResolvedNeed need, int extraBuyCost, Map<Integer, Integer> extraMissing) {
            this.need = need;
            this.stateAfter = null;
            this.extraBuyCost = extraBuyCost;
            this.extraMissing = extraMissing;
        }

        boolean isStateCandidate() {
            return stateAfter != null;
        }
    }

    private boolean containsAnyDailyCrafted(ResolvedNeed need) {
        if (need == null) return false;

        if (DailyCrafts.isDailyOutput(need.getItemId()) && need.getQtyCrafted() > 0) {
            return true;
        }

        for (ResolvedNeed child : need.getChildren()) {
            if (containsAnyDailyCrafted(child)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasAnyDailyCrafted(ResolvedNeed need) {
        return containsAnyDailyCrafted(need);
    }

    private int estimateDirectCraftFloor(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx
                                        ) {
        int sum = 0;

        for (RecipeRepository.Ingredient ing : recipe.ingredients) {
            int buyUnit = resolveDirectBuyUnit(ing.itemId, ctx.tp, ctx.settings);
            if (buyUnit <= 0) {
                return Integer.MAX_VALUE;
            }
            sum += buyUnit * ing.count;
        }

        return sum;
    }
}