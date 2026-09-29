package craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain entry point for explaining one execution of a selected recipe
 * (TARGET_ARCHITECTURE.md section 13.3, DOMAIN_SPEC.md section 44).
 *
 * <p>It runs the authoritative resolver - the same {@link RecipeSimulator}/{@link CraftingResolver}
 * that produces the economic results, with the same eligibility, binding, valuation and
 * craft-versus-buy rules - with trace recording switched on, and reports the simulation's
 * <em>first</em> craft. There is no second resolver, no re-derivation of decisions from prices or
 * recipes, and no explanation of the craft that would follow an exhausted simulation.
 *
 * <p>Each call works only from the inputs it is handed: it copies them into its own planning state,
 * builds its own context, and loads nothing. The caller's inventory, budget and daily state are
 * never mutated, and no state is carried between calls - a {@code SingleCraftExplainer} instance
 * holds none.
 *
 * <p>Producing an explanation is opt-in per selected recipe. Table calculations go through
 * {@link CraftingPlanner}, whose resolver does not trace and whose rows therefore build no
 * semantic tree.
 */
public final class SingleCraftExplainer {

    /**
     * Explains one craft on the individual-character path used by Crafting Discovery
     * (CURRENT_ARCHITECTURE.md section 5.2), whose inputs match
     * {@link CraftingPlanner#evaluateAll(List, Map, Map, Map, CraftingSettings, Set)}.
     *
     * @param recipe            the selected recipe; its output count is what the root requests.
     * @param recipes           every recipe the calculation may resolve through.
     * @param sellableInventory ordinary tradable owned quantity.
     * @param boundInventory    bound owned quantity, consumed first and never charged TP
     *                          opportunity cost (DOMAIN_SPEC.md section 11.1).
     */
    public SingleCraftExplanation explainIndividual(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> boundInventory,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds
                                                   ) {

        PlannerContext ctx = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds);

        return explain(recipe, ctx, new PlanState(sellableInventory, boundInventory));
    }

    /**
     * Explains one craft on the coordinated-roster path used by Crafting Profit
     * (CURRENT_ARCHITECTURE.md section 5.1), whose inputs match
     * {@link CraftingPlanner#evaluateAllCoordinated(List, Map, Map, Map, List, Map,
     * CraftingSettings, Set)}. Each craft step is assigned to an eligible roster character exactly
     * as the table calculation assigns it, and the assignment is part of the explanation.
     */
    public SingleCraftExplanation explainCoordinated(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds
                                                    ) {

        PlannerContext ctx = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds, roster);

        return explain(recipe, ctx,
                new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory));
    }

    private SingleCraftExplanation explain(Recipe recipe, PlannerContext ctx, PlanState initialState) {
        RecipeSimulator simulator = new RecipeSimulator(new CraftingResolver(true), true);
        RecipeSimulationResult simulation = simulator.simulateRecipe(recipe, ctx, initialState);

        ResolvedNeed accepted = simulation.getFirstCraft();
        if (accepted != null) {
            return explanationOf(recipe, toNode(accepted, BlockedReason.NONE));
        }

        // The first craft was attempted and rejected: its reasons are still an explanation
        // (TARGET_ARCHITECTURE.md section 13.3), unlike the absence of a result.
        ResolvedNeed rejected = simulation.getBlockedAttempt();
        if (rejected != null) {
            return explanationOf(recipe, toNode(rejected, simulation.getBlockedReason()));
        }

        return SingleCraftExplanation.unavailable(
                recipe.recipeId, recipe.outputItemId, recipe.outputCount);
    }

    private SingleCraftExplanation explanationOf(Recipe recipe, CraftTraceNode root) {
        return new SingleCraftExplanation(
                recipe.recipeId, recipe.outputItemId, recipe.outputCount, true, root);
    }

    /**
     * Maps one resolved requirement onto its explanation.
     *
     * @param rejectionReason a reason the whole attempt was rejected above this requirement -
     *                        {@link BlockedReason#INSUFFICIENT_BUDGET} for a craft that resolved
     *                        but could not be afforded. {@link BlockedReason#NONE} everywhere else,
     *                        including for every child.
     */
    private CraftTraceNode toNode(ResolvedNeed need, BlockedReason rejectionReason) {
        ResolvedNeed.NodeTrace trace = need.getTrace();
        ResolvedNeed craftAttempt = trace == null ? null : trace.getCraftAttempt();

        // The craft path's own facts live on the attempt node, except where the resolver blocked
        // the requirement itself before any attempt could be made and recorded them in place.
        ResolvedNeed.NodeTrace craftTrace = craftAttempt != null ? craftAttempt.getTrace() : trace;

        List<CraftTraceNode> children = new ArrayList<>();
        if (craftAttempt != null) {
            // Repeated items in different branches stay separate occurrences: each child is mapped
            // where it occurs, and a cycle ends in the resolver's finite CYCLE_DETECTED node.
            for (ResolvedNeed child : craftAttempt.getChildren()) {
                children.add(toNode(child, BlockedReason.NONE));
            }
        }

        // An attempted craft that was rejected and rolled back contributed no units here, so its
        // execution count stays out of the explanation; its identity and requirements remain.
        boolean crafted = need.getQtyCrafted() > 0 && craftTrace != null;

        List<BlockedReason> blockedReasons = blockedReasonsOf(need, craftAttempt, rejectionReason);
        boolean complete = need.isFullySatisfied();

        return new CraftTraceNode(
                need.getItemId(),
                need.getQtyRequested(),
                need.getQtyFromInventory(),
                need.getQtyCrafted(),
                need.getQtyBought(),
                need.getQtyBlocked(),
                craftTrace == null ? null : craftTrace.getSelectedRecipeId(),
                crafted ? craftTrace.getCraftCount() : 0,
                crafted ? craftTrace.getProducedQuantity() : 0,
                craftTrace == null ? null : craftTrace.getAssignedCharacter(),
                methodsOf(need),
                statesOf(need, trace, blockedReasons, rejectionReason),
                blockedReasons,
                complete ? need.getBuyCostCopper() : null,
                complete ? need.getOpportunityCostCopper() : null,
                complete ? need.getEffectiveCostCopper() : null,
                children
        );
    }

    private List<AcquisitionMethod> methodsOf(ResolvedNeed need) {
        List<AcquisitionMethod> methods = new ArrayList<>(3);
        if (need.getQtyFromInventory() > 0) methods.add(AcquisitionMethod.INVENTORY);
        if (need.getQtyCrafted() > 0) methods.add(AcquisitionMethod.CRAFT);
        if (need.getQtyBought() > 0) methods.add(AcquisitionMethod.BUY);
        return methods;
    }

    /**
     * Every explicit reason the resolver established for this requirement: its own, then - because
     * the resolver reports only one reason per node - the reason the craft path attempted for it
     * failed, which is what distinguishes a cycle, a daily limit or a disallowed recipe from the
     * generic reason recorded above it.
     */
    private List<BlockedReason> blockedReasonsOf(ResolvedNeed need,
                                                 ResolvedNeed craftAttempt,
                                                 BlockedReason rejectionReason) {
        List<BlockedReason> reasons = new ArrayList<>(3);

        addReason(reasons, need.getBlockedReason());
        if (craftAttempt != null) {
            addReason(reasons, craftAttempt.getBlockedReason());
        }
        addReason(reasons, rejectionReason);

        return reasons;
    }

    private void addReason(List<BlockedReason> reasons, BlockedReason reason) {
        if (reason != BlockedReason.NONE && !reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private List<ResolutionState> statesOf(ResolvedNeed need,
                                           ResolvedNeed.NodeTrace trace,
                                           List<BlockedReason> blockedReasons,
                                           BlockedReason rejectionReason) {
        List<ResolutionState> states = new ArrayList<>(3);

        if (need.getQtyBlocked() > 0 || rejectionReason != BlockedReason.NONE) {
            states.add(ResolutionState.BLOCKED);
        }
        if (blockedReasons.contains(BlockedReason.PRICE_UNAVAILABLE)) {
            states.add(ResolutionState.PRICE_UNAVAILABLE);
        }
        if (blockedReasons.contains(BlockedReason.DAILY_LIMIT)) {
            states.add(ResolutionState.DAILY_LIMIT);
        }
        if (trace != null && trace.isUnvaluedNonTradable()) {
            states.add(ResolutionState.UNVALUED_NONTRADEABLE);
        }

        return states;
    }
}
