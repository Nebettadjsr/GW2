package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CraftingResolver {

    private final Map<Integer, Integer> directBuyUnitCache = new HashMap<>();
    private final Map<Integer, Integer> directSellUnitCache = new HashMap<>();
    private final Map<Integer, RecipeRepository.Recipe> firstRecipeCache = new HashMap<>();

    public ResolveResult resolveOneCraft(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState state
                                        ) {
        if (ctx.isCoordinated() && (!ctx.allowedRecipeIds.contains(recipe.recipeId)
                || ctx.eligibleCharactersFor(recipe).isEmpty())) {
            return new ResolveResult(blockedNeed(recipe.outputItemId, recipe.outputCount,
                    BlockedReason.RECIPE_NOT_ALLOWED));
        }
        ResolvedNeed root = resolveNeed(recipe.outputItemId, recipe.outputCount, ctx, state, false, null, null);
        return new ResolveResult(root);
    }

    public ResolvedNeed resolveNeed(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            boolean allowDirectBuy
                                   ) {
        return resolveNeed(itemId, qtyRequested, ctx, state, allowDirectBuy, null, null);
    }

    private ResolvedNeed resolveNeed(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            boolean allowDirectBuy,
            String parentDiscipline,
            String assignedCharacter
                                   ) {
        ResolvedNeed result = new ResolvedNeed(itemId, qtyRequested);

        int remaining = qtyRequested;

        // 1) Use owned inventory first, if enabled
        if (ctx.settings.useOwnMats) {
            // DOMAIN_SPEC.md section 11.1 / DQ-007: account-bound/soulbound-usable owned
            // quantity is consumed before ordinary tradable owned quantity and never charged
            // TP opportunity cost, since it cannot be sold on the Trading Post either way.
            // assignedCharacter (DOMAIN_SPEC.md section 2.2.1) further restricts soulbound
            // consumption to the character actually executing the step needing this item.
            PlanState.InventoryConsumption consumption =
                    state.consumeInventoryWithBinding(itemId, remaining, assignedCharacter);
            int usedFromInventory = consumption.total();
            result.setQtyFromInventory(usedFromInventory);

            int oppCost = consumption.usedSellable() * resolveDirectSellUnit(itemId, ctx.tp, ctx.settings);
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
        boolean buyPriceUnavailable = false;
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
            } else {
                // DOMAIN_SPEC.md section 21: no usable TP quote is not the same as a free item.
                buyPriceUnavailable = true;
            }
        }

        // 3) Evaluate craft on a COPY of state
        CandidateEval craftEval = null;
        RecipeRepository.Recipe firstRecipe = firstRecipeFor(itemId, ctx, parentDiscipline);

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
            ResolvedNeed craftNeed = tryCraft(itemId, remaining, ctx, craftState, parentDiscipline);
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
            } else if (craftEval != null && craftEval.need.getBlockedReason() != BlockedReason.NONE) {
                result.setBlockedReason(craftEval.need.getBlockedReason());
            } else if (buyPriceUnavailable) {
                result.setBlockedReason(BlockedReason.PRICE_UNAVAILABLE);
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
            PlanState state,
            String parentDiscipline
                                 ) {
        RecipeRepository.Recipe recipe = firstRecipeFor(itemId, ctx, parentDiscipline);
        if (recipe == null) {
            if (hasOnlyDisallowedRecipes(itemId, ctx)) {
                return blockedNeed(itemId, qtyRequested, BlockedReason.RECIPE_NOT_ALLOWED);
            }
            return null;
        }

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        // Daily mode = BUY -> this node may not be crafted directly
        if (isDaily && ctx.settings.dailyBuyInsteadOfCraft) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.DAILY_LIMIT);
        }

        if (!ctx.isCoordinated()) {
            return tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, null);
        }

        // DOMAIN_SPEC.md section 2.2.1: coordinated multi-character planning - a recipe may only
        // be performed by a character who has one of its disciplines at a sufficient rating.
        // Soulbound ingredients (below, via the assigned character's own PlanState.
        // characterBoundInventory) are then only usable by whichever eligible character actually
        // performs this specific craft step; the produced item itself remains a transferable
        // intermediate usable by any later step regardless of who crafted it.
        List<String> eligible = ctx.eligibleCharactersFor(recipe);
        if (eligible.isEmpty()) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.RECIPE_NOT_ALLOWED);
        }

        ResolvedNeed best = null;
        PlanState bestState = null;

        for (String character : eligible) {
            PlanState trial = new PlanState(state);
            ResolvedNeed attempt = tryCraftAssigned(itemId, qtyRequested, recipe, ctx, trial, character);
            if (isBetterCraftAttempt(attempt, best)) {
                best = attempt;
                bestState = trial;
            }
        }

        state.copyFrom(bestState);
        return best;
    }

    /**
     * Performs (or attempts) one craft-step batch of {@code recipe} for {@code itemId}, with
     * ingredient consumption attributed to {@code assignedCharacter} (may be {@code null} for
     * the single-character/legacy path, where every soulbound quantity already lives in the
     * shared {@link PlanState#boundInventory} rather than per-character).
     */
    private ResolvedNeed tryCraftAssigned(
            int itemId,
            int qtyRequested,
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            String assignedCharacter
                                          ) {
        // cycle protection
        if (state.visiting.contains(itemId)) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.CYCLE_DETECTED);
        }

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

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
            BlockedReason unsatisfiedChildReason = BlockedReason.NONE;

            for (RecipeRepository.Ingredient ing : recipe.ingredients) {
                int childQtyNeeded = ing.count * times;

                ResolvedNeed child = resolveNeed(ing.itemId, childQtyNeeded, ctx, state, true,
                        recipe.disciplinesText, assignedCharacter);
                craftResult.addChild(child);
                craftResult.addCostsFromChild(child);

                if (!child.isFullySatisfied()) {
                    allChildrenSatisfied = false;
                    if (unsatisfiedChildReason == BlockedReason.NONE) {
                        unsatisfiedChildReason = child.getBlockedReason();
                    }
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
                craftResult.setBlockedReason(
                        unsatisfiedChildReason != BlockedReason.NONE ? unsatisfiedChildReason : BlockedReason.NO_RECIPE
                );
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

    private ResolvedNeed blockedNeed(int itemId, int qtyRequested, BlockedReason reason) {
        ResolvedNeed blocked = new ResolvedNeed(itemId, qtyRequested);
        blocked.setQtyBlocked(qtyRequested);
        blocked.setBlockedReason(reason);
        blocked.determineMode();
        return blocked;
    }

    /**
     * Compares two candidate character assignments for the same craft step: fully satisfied
     * beats partially satisfied, and among fully-satisfied candidates the lowest total effective
     * economic cost wins (DOMAIN_SPEC.md section 22), otherwise the least-blocked candidate wins.
     * Soulbound ingredients never carry an opportunity-cost difference between characters, so
     * this reduces to "prefer whichever eligible character can actually supply this step."
     */
    private boolean isBetterCraftAttempt(ResolvedNeed candidate, ResolvedNeed currentBest) {
        if (currentBest == null) return true;

        boolean candidateSatisfied = candidate.isFullySatisfied();
        boolean bestSatisfied = currentBest.isFullySatisfied();

        if (candidateSatisfied != bestSatisfied) {
            return candidateSatisfied;
        }

        if (candidateSatisfied) {
            return candidate.getEffectiveCostCopper() <= currentBest.getEffectiveCostCopper();
        }

        return candidate.getQtySatisfied() > currentBest.getQtySatisfied();
    }

    private CandidateEval chooseBetterCandidate(CandidateEval buyEval, CandidateEval craftEval) {
        boolean buyValid = buyEval != null
                && buyEval.need != null
                && buyEval.need.getQtyBlocked() == 0;

        boolean craftValid = craftEval != null
                && craftEval.need != null
                && craftEval.need.getQtyBlocked() == 0;

        if (buyValid && craftValid) {
            // DOMAIN_SPEC.md section 22: choose the lowest total effective economic cost
            // (cash + opportunity cost), not the lowest immediate cash cost.
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

//    public RecipeRepository.Recipe firstRecipeFor(int itemId, PlannerContext ctx) {
//
//        RecipeRepository.Recipe cached = firstRecipeCache.get(itemId);
//        if (cached != null) return cached;
//
//        List<RecipeRepository.Recipe> list = ctx.recipesByOutput.get(itemId);
//
//        RecipeRepository.Recipe result = (list == null || list.isEmpty()) ? null : list.get(0);
//
//        firstRecipeCache.put(itemId, result);
//
//        return result;
//    }

    /**
     * DOMAIN_SPEC.md section 30 / DQ-003: prefer a valid recipe matching the parent
     * recipe's crafting discipline; among same-discipline candidates prefer the lowest
     * effective cost; otherwise prefer the lowest effective cost among all valid candidates.
     */
    public RecipeRepository.Recipe firstRecipeFor(int itemId, PlannerContext ctx, String parentDiscipline) {
        List<RecipeRepository.Recipe> list = ctx.recipesByOutput.get(itemId);
        if (list == null || list.isEmpty()) {
            return null;
        }

        RecipeRepository.Recipe bestSameDiscipline = null;
        int bestSameDisciplineCost = Integer.MAX_VALUE;
        RecipeRepository.Recipe bestOverall = null;
        int bestOverallCost = Integer.MAX_VALUE;

        for (RecipeRepository.Recipe recipe : list) {
            if (!ctx.allowedRecipeIds.contains(recipe.recipeId)
                    || (ctx.isCoordinated() && ctx.eligibleCharactersFor(recipe).isEmpty())) {
                continue;
            }

            int cost = estimateDirectCraftFloor(recipe, ctx);

            if (bestOverall == null || cost < bestOverallCost) {
                bestOverallCost = cost;
                bestOverall = recipe;
            }

            if (sharesDiscipline(parentDiscipline, recipe.disciplinesText)
                    && (bestSameDiscipline == null || cost < bestSameDisciplineCost)) {
                bestSameDisciplineCost = cost;
                bestSameDiscipline = recipe;
            }
        }

        return bestSameDiscipline != null ? bestSameDiscipline : bestOverall;
    }

    /**
     * DOMAIN_SPEC.md section 42: distinguishes "no recipe exists for this item at all"
     * (NO_RECIPE) from "recipe(s) exist but every candidate is filtered out by
     * ctx.allowedRecipeIds" (RECIPE_NOT_ALLOWED) - firstRecipeFor(...) returns null for both.
     */
    private boolean hasOnlyDisallowedRecipes(int itemId, PlannerContext ctx) {
        List<RecipeRepository.Recipe> list = ctx.recipesByOutput.get(itemId);
        if (list == null || list.isEmpty()) {
            return false;
        }

        for (RecipeRepository.Recipe recipe : list) {
            if (ctx.allowedRecipeIds.contains(recipe.recipeId)
                    && (!ctx.isCoordinated() || !ctx.eligibleCharactersFor(recipe).isEmpty())) {
                return false;
            }
        }

        return true;
    }

    private boolean sharesDiscipline(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        Set<String> disciplinesA = splitDisciplines(a);
        for (String discipline : splitDisciplines(b)) {
            if (disciplinesA.contains(discipline)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> splitDisciplines(String disciplinesText) {
        Set<String> result = new HashSet<>();
        for (String part : disciplinesText.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
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
