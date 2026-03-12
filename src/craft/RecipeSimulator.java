package craft;

import repo.RecipeRepository;

import java.util.HashMap;
import java.util.Map;

public class RecipeSimulator {

    private final CraftingResolver resolver = new CraftingResolver();

    public RecipeSimulationResult simulateRecipe(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState baseState
                                                ) {
        RecipeSimulationResult result =
                new RecipeSimulationResult(recipe.recipeId, recipe.outputItemId);

        PlanState state = new PlanState(baseState);

        // Phase 1: consume zero-cash / own-mats crafts first
        if (ctx.settings.useOwnMats && ctx.settings.allowBuying) {
            CraftingSettings noBuySettings = new CraftingSettings(
                    ctx.settings.useOwnMats,
                    false,
                    0,
                    ctx.settings.listingSell,
                    ctx.settings.listingBuy,
                    ctx.settings.dailyBuyInsteadOfCraft
            );

            PlannerContext noBuyCtx = new PlannerContext(
                    ctx.recipesByOutput,
                    ctx.tp,
                    noBuySettings
            );

            simulatePhase(recipe, noBuyCtx, state, result);
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
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            RecipeSimulationResult result
                              ) {
        while (true) {
            PlanState attemptState = new PlanState(state);

            ResolveResult rr = resolver.resolveOneCraft(recipe, ctx, attemptState);
            ResolvedNeed root = rr.getRoot();

            if (root.getQtySatisfied() < root.getQtyRequested()) {
                break;
            }

            int nextBuyTotal = result.getBuyCostTotal() + rr.getBuyCostCopper();

            if (ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper) {
                break;
            }

            state.copyFrom(attemptState);

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