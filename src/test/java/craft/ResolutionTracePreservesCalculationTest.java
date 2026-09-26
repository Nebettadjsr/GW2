package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Trace recording is opt-in and must change nothing: an ordinary table calculation resolves exactly
 * as before and builds no semantic tree at all, while a traced resolution reaches the same economic
 * outcome and the same accepted planning state (TARGET_ARCHITECTURE.md sections 13.3/13.5).
 */
class ResolutionTracePreservesCalculationTest {

    private static final int FINAL_ITEM = 999;
    private static final int INTERMEDIATE_ITEM = 100;
    private static final int RAW_ITEM = 200;

    private static final Recipe ROOT =
            recipe(1, FINAL_ITEM, List.of(ingredient(INTERMEDIATE_ITEM, 1), ingredient(RAW_ITEM, 2)));
    private static final Recipe INTERMEDIATE =
            recipe(2, INTERMEDIATE_ITEM, List.of(ingredient(RAW_ITEM, 1)));

    private static final Map<Integer, PriceQuote> TP = Map.of(
            INTERMEDIATE_ITEM, quote(40, 60),
            RAW_ITEM, quote(6, 9),
            FINAL_ITEM, quote(500, 700));

    private static final Map<Integer, Integer> INVENTORY = Map.of(RAW_ITEM, 7);

    private static PlannerContext context(CraftingSettings settings) {
        return new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(List.of(ROOT, INTERMEDIATE)),
                TP, settings, Set.of(1, 2));
    }

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying) {
        return new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);
    }

    @Test
    void ordinaryTableEvaluationBuildsNoTraceAtAll() {
        PlannerContext ctx = context(settings(true, true));

        RecipeSimulationResult simulation =
                new RecipeSimulator().simulateRecipe(ROOT, ctx, new PlanState(INVENTORY));

        assertNotNull(simulation.getFirstCraft());
        assertUntraced(simulation.getFirstCraft());
        assertUntraced(simulation.getLastCraft());
        assertUntraced(simulation.getBlockedAttempt());
    }

    @Test
    void tracedSimulationReachesTheSameEconomicOutcome() {
        for (boolean useOwnMats : new boolean[]{true, false}) {
            for (boolean allowBuying : new boolean[]{true, false}) {
                CraftingSettings settings = settings(useOwnMats, allowBuying);
                PlannerContext ctx = context(settings);

                RecipeSimulationResult plain =
                        new RecipeSimulator().simulateRecipe(ROOT, ctx, new PlanState(INVENTORY));
                RecipeSimulationResult traced = new RecipeSimulator(new CraftingResolver(true), false)
                        .simulateRecipe(ROOT, ctx, new PlanState(INVENTORY));

                String label = "useOwnMats=" + useOwnMats + ", allowBuying=" + allowBuying;
                assertEquals(plain.getCraftCount(), traced.getCraftCount(), label);
                assertEquals(plain.getBuyCostTotal(), traced.getBuyCostTotal(), label);
                assertEquals(plain.getOpportunityCostTotal(), traced.getOpportunityCostTotal(), label);
                assertEquals(plain.getTotalMissingToBuy(), traced.getTotalMissingToBuy(), label);
                assertEquals(plain.getBlockedReason(), traced.getBlockedReason(), label);
            }
        }
    }

    @Test
    void tracedResolutionLeavesTheSameAcceptedPlanningState() {
        PlannerContext ctx = context(settings(true, true));

        PlanState plainState = new PlanState(INVENTORY);
        ResolveResult plain = new CraftingResolver().resolveOneCraft(ROOT, ctx, plainState);

        PlanState tracedState = new PlanState(INVENTORY);
        ResolveResult traced = new CraftingResolver(true).resolveOneCraft(ROOT, ctx, tracedState);

        assertEquals(plainState.inventory, tracedState.inventory);
        assertEquals(plainState.boundInventory, tracedState.boundInventory);
        assertEquals(plainState.missingToBuy, tracedState.missingToBuy);
        assertEquals(plainState.buyCostCopper, tracedState.buyCostCopper);

        assertEquals(plain.getBuyCostCopper(), traced.getBuyCostCopper());
        assertEquals(plain.getOpportunityCostCopper(), traced.getOpportunityCostCopper());
        assertEquals(plain.getQtySatisfied(), traced.getQtySatisfied());
        assertEquals(plain.getQtyBlocked(), traced.getQtyBlocked());
    }

    @Test
    void multiCraftTableResultsAreUnchangedByTheFirstCraftStop() {
        PlannerContext ctx = context(settings(true, false));

        RecipeSimulationResult simulation =
                new RecipeSimulator().simulateRecipe(ROOT, ctx, new PlanState(INVENTORY));

        // Seven owned raw materials, three consumed per craft (two directly, one via the
        // intermediate), so two crafts complete and the third is blocked with buying disabled.
        assertEquals(2, simulation.getCraftCount());
        assertEquals(BlockedReason.BUYING_DISABLED, simulation.getBlockedReason());
        assertEquals(36, simulation.getOpportunityCostTotal());

        RecipeSimulationResult firstCraftOnly = new RecipeSimulator(new CraftingResolver(true), true)
                .simulateRecipe(ROOT, ctx, new PlanState(INVENTORY));

        assertEquals(1, firstCraftOnly.getCraftCount(), "an explanation covers one craft only");
        assertEquals(18, firstCraftOnly.getOpportunityCostTotal());
    }

    @Test
    void plannerResultsAreUnchanged() {
        Map<Integer, CraftResult> results = new CraftingPlanner().evaluateAll(
                List.of(ROOT, INTERMEDIATE), INVENTORY, Map.of(), TP, settings(true, false), Set.of(1, 2));

        CraftResult root = results.get(1);
        assertEquals(2, root.craftableCount);
        assertEquals(BlockedReason.BUYING_DISABLED, root.blockedReason);
        assertEquals(0, root.buyCostCopper);
        assertEquals(18, root.matsSellValueCopper);
        assertNull(root.tree, "table rows must not eagerly carry a resolution tree");
    }

    private static void assertUntraced(ResolvedNeed need) {
        if (need == null) {
            return;
        }

        assertNull(need.getTrace(), "item " + need.getItemId() + " must carry no trace");
        for (ResolvedNeed child : need.getChildren()) {
            assertUntraced(child);
        }
    }
}
