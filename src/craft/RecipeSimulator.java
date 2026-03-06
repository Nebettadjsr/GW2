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

        long start = System.currentTimeMillis();

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

            // put the hard cap HERE
//            int hardCap = ctx.settings.allowBuying ? 250 : 10000;
            if (result.getCraftCount() >= 250) {
                break;
            }
        }

        long end = System.currentTimeMillis();
        System.out.println("DONE recipeId=" + recipe.recipeId
                                   + " outputItemId=" + recipe.outputItemId
                                   + " crafts=" + result.getCraftCount()
                                   + " tookMs=" + (end - start));

        return result;
    }
}