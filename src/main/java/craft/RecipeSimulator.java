package craft;

import java.util.HashMap;
import java.util.Map;

public class RecipeSimulator {

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
        RecipeSimulationResult result =
                new RecipeSimulationResult(recipe.recipeId, recipe.outputItemId);

        PlanState state = new PlanState(baseState);

        // Phase 1: when inventory use and buying are both enabled, prefer fully-owned paths first.
        if (ctx.settings.useOwnMats && ctx.settings.allowBuying) {
            // Same no-buy settings this phase has always used; withBuyingDisabled() additionally
            // shares ctx's memo tables instead of giving every recipe's phase 1 an empty one.
            simulatePhase(recipe, ctx.withBuyingDisabled(), state, result);
        }

        // Continue with the requested buying policy. With buying disabled, every recursive
        // resolver call sees buying disabled too; daily crafting remains independently controlled.
        simulatePhase(recipe, ctx, state, result);

        return result;
    }

    private void simulatePhase(
            Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            RecipeSimulationResult result
                              ) {
        while (true) {
            if (acceptedCraftLimit > 0 && result.getCraftCount() >= acceptedCraftLimit) {
                break;
            }

            // Each additional batch is attempted on the live state and undone if it turns out to
            // be unaffordable or unsatisfiable; the accepted batches stay. Previously every one of
            // these up-to-250 iterations copied the entire state before attempting
            // (STORY-PERF-001).
            int mark = state.mark();

            ResolveResult rr = resolver.resolveOneCraft(recipe, ctx, state);
            ResolvedNeed root = rr.getRoot();

            if (root.getQtySatisfied() < root.getQtyRequested()) {
                state.rollbackTo(mark);
                result.setBlockedReason(root.getBlockedReason());
                result.setBlockedAttempt(root);
                break;
            }

            int nextBuyTotal = result.getBuyCostTotal() + rr.getBuyCostCopper();

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

            if (result.getCraftCount() == 0) {
                result.setFirstCraft(root);
            }

            if (captureAcceptedCrafts) result.addAcceptedCraft(root);

            result.setLastCraft(root);
            result.incrementCraftCount();
            result.addBuyCost(rr.getBuyCostCopper());
            result.addOpportunityCost(rr.getOpportunityCostCopper());

            Map<Integer, Integer> deltaMissing = new HashMap<>();
            for (var e : state.missingToBuy.entrySet()) {
                int already = result.getTotalMissingToBuy().getOrDefault(e.getKey(), 0);
                int delta = e.getValue() - already;
                if (delta > 0) {
                    deltaMissing.put(e.getKey(), delta);
                }
            }
            result.mergeMissing(deltaMissing);

            if (result.getCraftCount() >= 250) {
                break;
            }
        }
    }
}
