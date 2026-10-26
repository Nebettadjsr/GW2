package craft;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CraftingResolver {

    private final Map<Integer, Integer> directBuyUnitCache = new HashMap<>();
    private final Map<Integer, Integer> directSellUnitCache = new HashMap<>();
    private final Map<Integer, OwnedValue> ownedValueCache = new HashMap<>();

    /** Exact unit value: keep recipe batch fractions until the consumed quantity is known. */
    private record OwnedValue(BigInteger copper, BigInteger units) implements Comparable<OwnedValue> {
        static final OwnedValue ZERO = of(0);

        OwnedValue {
            BigInteger divisor = copper.gcd(units);
            copper = copper.divide(divisor);
            units = units.divide(divisor);
        }

        static OwnedValue of(int copper) {
            return new OwnedValue(BigInteger.valueOf(Math.max(copper, 0)), BigInteger.ONE);
        }

        OwnedValue add(OwnedValue other) {
            return new OwnedValue(copper.multiply(other.units).add(other.copper.multiply(units)),
                    units.multiply(other.units));
        }

        OwnedValue times(int quantity) {
            return new OwnedValue(copper.multiply(BigInteger.valueOf(quantity)), units);
        }

        OwnedValue per(int quantity) {
            return new OwnedValue(copper, units.multiply(BigInteger.valueOf(quantity)));
        }

        boolean isZero() { return copper.signum() == 0; }

        int costOf(int quantity) {
            // Round the complete consumed quantity up once, never each intermediate unit.
            BigInteger total = copper.multiply(BigInteger.valueOf(quantity));
            return total.add(units).subtract(BigInteger.ONE).divide(units)
                    .min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact();
        }

        @Override public int compareTo(OwnedValue other) {
            return copper.multiply(other.units).compareTo(other.copper.multiply(units));
        }
    }

    /**
     * When true, every resolved requirement additionally records the explanation facts a semantic
     * trace needs (STORY-DOM-020, see {@link ResolvedNeed.NodeTrace}). Off for every table
     * calculation, whose resolution is unchanged and whose nodes carry no trace at all.
     */
    private final boolean tracing;

    public CraftingResolver() {
        this(false);
    }

    /** @param tracing see {@link #tracing}; only the single-craft explanation turns this on. */
    public CraftingResolver(boolean tracing) {
        this.tracing = tracing;
    }

    public ResolveResult resolveOneCraft(
            Recipe recipe,
            PlannerContext ctx,
            PlanState state
                                        ) {
        if (ctx.isCoordinated() && (!ctx.allowedRecipeIds.contains(recipe.recipeId)
                || ctx.eligibleCharactersFor(recipe).isEmpty())) {
            return new ResolveResult(blockedNeed(recipe.outputItemId, recipe.outputCount,
                    BlockedReason.RECIPE_NOT_ALLOWED, recipe, null));
        }
        if (!ctx.allowedRecipeIds.contains(recipe.recipeId)) {
            return new ResolveResult(blockedNeed(recipe.outputItemId, recipe.outputCount,
                    BlockedReason.RECIPE_NOT_ALLOWED, recipe, null));
        }
        // DOMAIN_SPEC.md sections 28/30: the root is one execution of exactly this recipe. Owned or
        // bought finished output and alternative recipes for the same output never satisfy it; only
        // its ingredients go through ordinary owned/buy/craft acquisition.
        int mark = state.mark();
        ResolvedNeed craftNeed = tryCraft(recipe.outputItemId, recipe.outputCount, ctx, state, null, recipe);
        // Same node shape as an ordinary requirement resolved by crafting, so rows and trees agree.
        ResolvedNeed root = new ResolvedNeed(recipe.outputItemId, recipe.outputCount);
        if (craftNeed.getQtyBlocked() > 0) {
            state.rollbackTo(mark);
            if (tracing) root.traceForWrite().setCraftAttempt(craftNeed);
            root.setQtyBlocked(recipe.outputCount);
            root.setBlockedReason(craftNeed.getBlockedReason());
            root.determineMode();
            return new ResolveResult(root);
        }
        if (tracing) root.traceForWrite().setCraftAttempt(craftNeed);
        root.setQtyCrafted(craftNeed.getQtyCrafted());
        root.setBuyCostCopper(craftNeed.getBuyCostCopper());
        root.setOpportunityCostCopper(craftNeed.getOpportunityCostCopper());
        if (craftNeed.hasUnvaluedOwnedInput()) root.setUnvaluedOwnedInput(true);
        for (ResolvedNeed child : craftNeed.getChildren()) root.addChild(child);
        root.determineMode();
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

        int reused = state.consumeSurplus(itemId, qtyRequested);
        result.setQtyCrafted(reused);
        int remaining = qtyRequested - reused;

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

            int sellUnit = consumption.usedSellable() > 0 ? resolveDirectSellUnit(itemId, ctx.tp, ctx.settings) : 0;
            int oppCost = consumption.usedSellable() * sellUnit;
            boolean unvalued = false;
            if (consumption.usedSellable() > 0 && sellUnit <= 0) {
                OwnedValue fallback = resolveOwnedFallbackValue(itemId, ctx);
                int consumed = state.consumedSellableQuantity(itemId);
                oppCost = fallback.costOf(consumed) - fallback.costOf(consumed - consumption.usedSellable());
                unvalued = fallback.isZero();
            }
            result.setOpportunityCostCopper(oppCost);

            if (unvalued) {
                // DOMAIN_SPEC.md section 11.2: this quantity was valued at zero because no value
                // could be established for it, which is not the same as it being free.
                result.setUnvaluedOwnedInput(true);
                if (tracing) result.traceForWrite().markUnvaluedNonTradable();
            }

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

                buyEval = new CandidateEval(
                        buyNeed,
                        buyNeed.getBuyCostCopper()
                );
            } else {
                // DOMAIN_SPEC.md section 21: no usable TP quote is not the same as a free item.
                buyPriceUnavailable = true;
            }
        }

        // 3) Evaluate craft speculatively: performed on the live state and undone below if the
        //    craft is not the candidate finally chosen (this used to run on a full copy of state).
        CandidateEval craftEval = null;
        int craftMark = state.mark();
        ResolvedNeed craftNeed = tryCraft(itemId, remaining, ctx, state, parentDiscipline);
        if (craftNeed != null) {
            craftEval = new CandidateEval(craftNeed);
        } else {
            state.rollbackTo(craftMark);
            craftMark = -1;
        }

        // 4) Choose better candidate
        CandidateEval chosen = chooseBetterCandidate(buyEval, craftEval);

        if (chosen != null) {
            if (chosen.isStateCandidate()) {
                // The craft attempt is already applied to state; keeping it is the whole point.
                assert craftMark >= 0;
            } else {
                // Buying won: undo the speculative craft before charging the purchase.
                if (craftMark >= 0) state.rollbackTo(craftMark);

                state.addBuyCost(chosen.extraBuyCost);

            }

            ResolvedNeed chosenNeed = chosen.need;

            if (tracing && chosen.isStateCandidate()) {
                // Only the accepted craft is linked; a craft that lost to buying was rolled back
                // above and its node is unreachable from here.
                result.traceForWrite().setCraftAttempt(chosenNeed);
            }

            result.setQtyCrafted(reused + chosenNeed.getQtyCrafted());
            result.setQtyBought(chosenNeed.getQtyBought());
            result.setQtyBlocked(chosenNeed.getQtyBlocked());
            result.setBuyCostCopper(result.getBuyCostCopper() + chosenNeed.getBuyCostCopper());
            result.setOpportunityCostCopper(result.getOpportunityCostCopper() + chosenNeed.getOpportunityCostCopper());
            if (chosenNeed.hasUnvaluedOwnedInput()) result.setUnvaluedOwnedInput(true);

            if (chosenNeed.getBlockedReason() != BlockedReason.NONE) {
                result.setBlockedReason(chosenNeed.getBlockedReason());
            }

            for (ResolvedNeed child : chosenNeed.getChildren()) {
                result.addChild(child);
            }
        } else {
            // Neither candidate is usable: the speculative craft leaves no trace, exactly as the
            // discarded trial copy did before.
            if (craftMark >= 0) state.rollbackTo(craftMark);

            if (tracing && craftEval != null) {
                // TARGET_ARCHITECTURE.md section 13.3: a blocked requirement still shows the
                // ingredient requirements of the craft path that was attempted for it. The attempt's
                // state changes are rolled back above; only its explanatory facts survive, and this
                // node's own quantities and costs below remain those of the blocked requirement.
                result.traceForWrite().setCraftAttempt(craftEval.need);
            }

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
        return tryCraft(itemId, qtyRequested, ctx, state, parentDiscipline, null);
    }

    /** @param fixedRecipe when non-null, the only recipe that may be executed (root craft). */
    private ResolvedNeed tryCraft(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            String parentDiscipline,
            Recipe fixedRecipe
                                 ) {
        if (fixedRecipe == null) {
            return tryRecipeCandidates(itemId, qtyRequested, ctx, state, parentDiscipline);
        }
        Recipe recipe = fixedRecipe;

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        // Daily outputs may be crafted only when the independent daily-crafts setting permits it.
        if (isDaily && !ctx.settings.allowDailyCrafts) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.DAILY_LIMIT, recipe, null);
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
            return blockedNeed(itemId, qtyRequested, BlockedReason.RECIPE_NOT_ALLOWED, recipe, null);
        }

        // A single candidate is always "best" (isBetterCraftAttempt(attempt, null) is always
        // true), so the speculative trial-copy-then-copyFrom dance below is unnecessary and,
        // being on the hot recursive path, materially expensive (STORY-PERF-001) - apply directly.
        if (eligible.size() == 1) {
            return tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, eligible.get(0));
        }

        // Each candidate is tried from the same starting point, then undone; the winner's net
        // effect is captured and re-applied at the end. Previously each candidate ran on its own
        // full copy of the state and the winning copy was copied back.
        ResolvedNeed best = null;
        PlanState.Delta bestEffect = null;
        int mark = state.mark();

        for (String character : eligible) {
            ResolvedNeed attempt = tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, character);
            if (isBetterCraftAttempt(attempt, best)) {
                best = attempt;
                bestEffect = state.captureDelta(mark);
            }
            state.rollbackTo(mark);
        }

        state.applyDelta(bestEffect);
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
            Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            String assignedCharacter
                                          ) {
        // cycle protection
        if (state.isVisiting(itemId)) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.CYCLE_DETECTED, recipe, assignedCharacter);
        }

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        state.beginVisiting(itemId);

        try {
            ResolvedNeed craftResult = new ResolvedNeed(itemId, qtyRequested);

            if (tracing) {
                ResolvedNeed.NodeTrace trace = craftResult.traceForWrite();
                trace.setSelectedRecipeId(recipe.recipeId);
                trace.setAssignedCharacter(assignedCharacter);
            }

            int timesNeeded = ceilDiv(qtyRequested, recipe.outputCount);
            int times = timesNeeded;

            // At most one daily craft operation is available in this planning state.
            if (isDaily) {
                int left = state.dailyLeft(itemId, 1);
                times = Math.min(timesNeeded, left);

                if (times <= 0) {
                    craftResult.setQtyBlocked(qtyRequested);
                    craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
                    craftResult.determineMode();
                    return craftResult;
                }

                state.setDailyLeft(itemId, left - times);
            }

            int produced = times * recipe.outputCount;
            int qtySatisfied = Math.min(produced, qtyRequested);

            boolean allChildrenSatisfied = true;
            BlockedReason unsatisfiedChildReason = BlockedReason.NONE;

            for (Ingredient ing : recipe.ingredients) {
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

            if (!allChildrenSatisfied) {
                craftResult.setQtyBlocked(qtyRequested);
                craftResult.setBlockedReason(
                        unsatisfiedChildReason != BlockedReason.NONE ? unsatisfiedChildReason : BlockedReason.NO_RECIPE
                );
                craftResult.determineMode();
                return craftResult;
            }

            craftResult.setQtyCrafted(qtySatisfied);
            state.addSurplus(itemId, produced - qtySatisfied);

            if (tracing) {
                craftResult.traceForWrite().setCraft(times, produced);
            }

            if (qtySatisfied < qtyRequested) {
                craftResult.setQtyBlocked(qtyRequested - qtySatisfied);
                craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
            }

            craftResult.determineMode();
            return craftResult;

        } finally {
            state.endVisiting(itemId);
        }
    }

    private ResolvedNeed tryRecipeCandidates(int itemId, int quantity, PlannerContext ctx,
                                             PlanState state, String parentDiscipline) {
        ResolvedNeed best = null;
        PlanState.Delta effect = null;
        boolean bestMatches = false;
        int mark = state.mark();
        List<Recipe> candidates = ctx.recipesByOutput.getOrDefault(itemId, List.of());
        if (candidates.size() == 1) {
            Recipe only = candidates.getFirst();
            if (ctx.allowedRecipeIds.contains(only.recipeId)
                    && (!ctx.isCoordinated() || !ctx.eligibleCharactersFor(only).isEmpty())) {
                return tryCraft(itemId, quantity, ctx, state, parentDiscipline, only);
            }
        }
        for (Recipe recipe : candidates) {
            if (!ctx.allowedRecipeIds.contains(recipe.recipeId)
                    || (ctx.isCoordinated() && ctx.eligibleCharactersFor(recipe).isEmpty())) continue;
            ResolvedNeed attempt = tryCraft(itemId, quantity, ctx, state, parentDiscipline, recipe);
            boolean matches = sharesDiscipline(parentDiscipline, recipe.disciplinesText);
            boolean preferDiscipline = best != null && attempt.isFullySatisfied()
                    && best.isFullySatisfied() && matches != bestMatches;
            if (preferDiscipline ? matches : isBetterCraftAttempt(attempt, best)) {
                best = attempt;
                bestMatches = matches;
                effect = state.captureDelta(mark);
            }
            state.rollbackTo(mark);
        }
        state.applyDelta(effect);
        return best != null ? best : hasOnlyDisallowedRecipes(itemId, ctx)
                ? blockedNeed(itemId, quantity, BlockedReason.RECIPE_NOT_ALLOWED) : null;
    }

    private ResolvedNeed blockedNeed(int itemId, int qtyRequested, BlockedReason reason) {
        ResolvedNeed blocked = new ResolvedNeed(itemId, qtyRequested);
        blocked.setQtyBlocked(qtyRequested);
        blocked.setBlockedReason(reason);
        blocked.determineMode();
        return blocked;
    }

    /**
     * As {@link #blockedNeed(int, int, BlockedReason)}, for the blocked paths where a specific
     * recipe (and possibly a character) was identified before the path turned out to be unusable;
     * while tracing, that identity is part of the explanation.
     */
    private ResolvedNeed blockedNeed(int itemId, int qtyRequested, BlockedReason reason,
                                     Recipe recipe, String assignedCharacter) {
        ResolvedNeed blocked = blockedNeed(itemId, qtyRequested, reason);

        if (tracing) {
            ResolvedNeed.NodeTrace trace = blocked.traceForWrite();
            if (recipe != null) {
                trace.setSelectedRecipeId(recipe.recipeId);
            }
            trace.setAssignedCharacter(assignedCharacter);
        }

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
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                                   ) {

        Integer cached = directBuyUnitCache.get(itemId);
        if (cached != null) return cached;

        PriceQuote q = tp.get(itemId);
        if (q == null) {
            directBuyUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingBuy ? q.buyUnit : q.sellUnit;
        int result = v == null ? 0 : v;

        directBuyUnitCache.put(itemId, result);
        return result;
    }

    /**
     * DOMAIN_SPEC.md 11.2's fallback for an owned item without a usable Trading Post side:
     * calculated crafting cost, then vendor sell value. Zero means unvalued, never free.
     */
    private OwnedValue resolveOwnedFallbackValue(int itemId, PlannerContext ctx) {
        OwnedValue cached = ownedValueCache.get(itemId);
        if (cached != null) return cached;
        OwnedValue value = fallbackValue(itemId, ctx, new HashSet<>());
        ownedValueCache.put(itemId, value);
        return value;
    }

    /** Crafting cost, else vendor value, else 0. */
    private OwnedValue fallbackValue(int itemId, PlannerContext ctx, Set<Integer> visiting) {
        OwnedValue crafted = craftingUnitValue(itemId, ctx, visiting);
        if (!crafted.isZero()) return crafted;
        return OwnedValue.of(ctx.vendorValues.getOrDefault(itemId, 0));
    }

    /** Compare real purchase/crafting routes recursively; vendor sell values are not buy offers. */
    private OwnedValue craftingUnitValue(int itemId, PlannerContext ctx, Set<Integer> visiting) {
        if (!visiting.add(itemId)) return OwnedValue.ZERO;
        try {
            OwnedValue best = OwnedValue.ZERO;
            for (Recipe recipe : ctx.recipesByOutput.getOrDefault(itemId, List.of())) {
                OwnedValue total = OwnedValue.ZERO;
                boolean valued = true;
                for (Ingredient ing : recipe.ingredients) {
                    OwnedValue unit = OwnedValue.of(resolveDirectBuyUnit(ing.itemId, ctx.tp, ctx.settings));
                    OwnedValue crafted = craftingUnitValue(ing.itemId, ctx, visiting);
                    if (!crafted.isZero() && (unit.isZero() || crafted.compareTo(unit) < 0)) unit = crafted;
                    if (unit.isZero()) unit = OwnedValue.of(ctx.vendorValues.getOrDefault(ing.itemId, 0));
                    if (unit.isZero()) { valued = false; break; }
                    total = total.add(unit.times(ing.count));
                }
                if (!valued || recipe.outputCount <= 0) continue;
                OwnedValue unitCost = total.per(recipe.outputCount);
                if (!unitCost.isZero() && (best.isZero() || unitCost.compareTo(best) < 0)) best = unitCost;
            }
            return best;
        } finally {
            visiting.remove(itemId);
        }
    }

    public int resolveDirectSellUnit(
            int itemId,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                                    ) {

        Integer cached = directSellUnitCache.get(itemId);
        if (cached != null) return cached;

        PriceQuote q = tp.get(itemId);
        if (q == null) {
            directSellUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingSell ? q.sellUnit : q.buyUnit;
        int result = v == null ? 0 : v;

        directSellUnitCache.put(itemId, result);
        return result;
    }


    /**
     * DOMAIN_SPEC.md section 42: distinguishes "no recipe exists for this item at all"
     * (NO_RECIPE) from "recipe(s) exist but every candidate is filtered out by
     * ctx.allowedRecipeIds" (RECIPE_NOT_ALLOWED).
     */
    private boolean hasOnlyDisallowedRecipes(int itemId, PlannerContext ctx) {
        List<Recipe> list = ctx.recipesByOutput.get(itemId);
        if (list == null || list.isEmpty()) {
            return false;
        }

        for (Recipe recipe : list) {
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
        /** True for the craft candidate, whose effect is already applied to the live PlanState. */
        final boolean stateCandidate;
        final int extraBuyCost;

        CandidateEval(ResolvedNeed need) {
            this.need = need;
            this.stateCandidate = true;
            this.extraBuyCost = 0;
        }

        CandidateEval(ResolvedNeed need, int extraBuyCost) {
            this.need = need;
            this.stateCandidate = false;
            this.extraBuyCost = extraBuyCost;
        }

        boolean isStateCandidate() {
            return stateCandidate;
        }
    }

}
