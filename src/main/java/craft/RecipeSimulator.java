package craft;

import java.util.HashMap;
import java.util.Map;

public class RecipeSimulator {

    private final CraftingResolver resolver = new CraftingResolver();

    public RecipeSimulationResult simulateRecipe(
            Recipe recipe,
            PlannerContext ctx,
            PlanState baseState
                                                ) {
        RecipeSimulationResult result =
                new RecipeSimulationResult(recipe.recipeId, recipe.outputItemId);

        PlanState state = new PlanState(baseState);

        // Phase 1: consume zero-cash / own-mats crafts first
        if (ctx.settings.useOwnMats && ctx.settings.allowBuying) {
            // Same no-buy settings this phase has always used; withBuyingDisabled() additionally
            // shares ctx's memo tables instead of giving every recipe's phase 1 an empty one.
            simulatePhase(recipe, ctx.withBuyingDisabled(), state, result);
        }

        // Phase 2: continue with buying enabled if originally requested
        if (ctx.settings.allowBuying) {
            simulatePhase(recipe, ctx, state, result);
        } else if (!(ctx.settings.useOwnMats && ctx.settings.allowBuying)) {
            // Normal no-buy mode when buying is disabled from the start
            simulatePhase(recipe, ctx, state, result);
        }

        return result;
    }

    private void simulatePhase(
            Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            RecipeSimulationResult result
                              ) {
        while (true) {
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
                break;
            }

            int nextBuyTotal = result.getBuyCostTotal() + rr.getBuyCostCopper();

            if (ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper) {
                state.rollbackTo(mark);
                result.setBlockedReason(BlockedReason.INSUFFICIENT_BUDGET);
                break;
            }

            result.setBlockedReason(BlockedReason.NONE);
            // Accepted: this batch is never revisited, so its undo information can be dropped -
            // without this the journal would grow across all 250 iterations.
            state.commitTo(mark);

            if (result.getCraftCount() == 0) {
                result.setFirstCraft(root);
            }

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