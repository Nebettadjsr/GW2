package craft;

import repo.RecipeRepository;

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

        while (true) {

            PlanState attemptState = new PlanState(state);

            ResolveResult rr = resolver.resolveOneCraft(recipe, ctx, attemptState);
            ResolvedNeed root = rr.getRoot();

            // If the craft cannot be fully satisfied -> stop
            if (root.getQtySatisfied() < root.getQtyRequested()) {
                break;
            }

            int nextBuyTotal = result.getBuyCostTotal() + rr.getBuyCostCopper();

            // Budget check BEFORE committing this craft
            if (ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper) {
                break;
            }

            // Commit chosen attempt
            state = attemptState;

            if (result.getCraftCount() == 0) {
                result.setFirstCraft(root);
            }

            result.setLastCraft(root);
            result.incrementCraftCount();

            result.addBuyCost(rr.getBuyCostCopper());
            result.addOpportunityCost(rr.getOpportunityCostCopper());

            result.mergeMissing(state.missingToBuy);

            if (result.getCraftCount() >= 250) {
                break;
            }
        }

        return result;
    }

    private ResolveResult simulateCrafts(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx,
            PlanState baseState,
            int crafts
                                        ) {

        PlanState state = new PlanState(baseState);

        ResolveResult last = null;

        for (int i = 0; i < crafts; i++) {

            ResolveResult rr = resolver.resolveOneCraft(recipe, ctx, state);
            ResolvedNeed root = rr.getRoot();

            if (root.getQtySatisfied() < root.getQtyRequested()) {
                return null;
            }

            if (ctx.settings.maxBuyCopper > 0 &&
                    (last != null ? last.getBuyCostCopper() : 0) + rr.getBuyCostCopper() > ctx.settings.maxBuyCopper) {
                return null;
            }

            last = rr;
        }

        return last;
    }
}