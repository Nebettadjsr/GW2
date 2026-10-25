package craft;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static craft.CraftTestFixtures.noQuote;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link CostEvaluator} result contract after STORY-DOM-025: it derives the two per-craft
 * economic inputs {@link CraftingPlanner} reads from it - DOMAIN_SPEC.md section 24/25's gross
 * output revenue and section 11.1's owned-material opportunity cost - and publishes no profit.
 *
 * <p>Profit belongs to section 26 and is computed once, in the planner, with the Trading Post fee
 * and the planner's own purchased-material basis ({@code CraftingPlannerProfitFeeTest} covers it).
 * The last case here pins the absence of a second one, so a pre-fee "profit" cannot reappear on
 * this result without a test saying so.
 */
class CostEvaluatorTest {

    private static CraftingSettings settings(boolean listingSell) {
        return new CraftingSettings(true, false, 0, listingSell, false, false);
    }

    /**
     * A simulation of {@code craftCount} executions whose first craft gave up owned material. Its
     * first craft also carries a deliberately non-zero buy cost, which this evaluator no longer
     * reads: nothing it returns may vary with it.
     */
    private static RecipeSimulationResult simulation(int recipeId,
                                                     int outputItemId,
                                                     int craftCount,
                                                     int opportunityCostCopper) {
        RecipeSimulationResult sim = new RecipeSimulationResult(recipeId, outputItemId);
        ResolvedNeed firstCraft = new ResolvedNeed(outputItemId, 1);
        firstCraft.setBuyCostCopper(999);
        firstCraft.setOpportunityCostCopper(opportunityCostCopper);
        sim.setFirstCraft(firstCraft);
        for (int i = 0; i < craftCount; i++) {
            sim.incrementCraftCount();
        }
        return sim;
    }

    private static CostEvaluationResult evaluate(Recipe recipe,
                                                 RecipeSimulationResult sim,
                                                 Map<Integer, PriceQuote> tp,
                                                 CraftingSettings settings) {
        return new CostEvaluator().evaluate(recipe, sim, tp, settings);
    }

    /** Instant sell: the output's buy quote, carrying the recipe's output quantity, gross. */
    @Test
    void instantSellRevenueIsTheOutputBuyQuoteTimesTheOutputCount() {
        Recipe recipe = recipe(1, 100, 3, List.of());
        Map<Integer, PriceQuote> tp = Map.of(100, quote(120, 150));

        CostEvaluationResult result = evaluate(recipe, simulation(1, 100, 1, 204), tp, settings(false));

        assertEquals(360, result.getRevenuePerCraft(), "3 outputs at the 120c buy quote");
        assertEquals(204, result.getOpportunityCostPerCraft());
    }

    /** Listing sell: the same rule against the sell quote. */
    @Test
    void listingSellRevenueIsTheOutputSellQuoteTimesTheOutputCount() {
        Recipe recipe = recipe(1, 100, 3, List.of());
        Map<Integer, PriceQuote> tp = Map.of(100, quote(120, 150));

        CostEvaluationResult result = evaluate(recipe, simulation(1, 100, 1, 204), tp, settings(true));

        assertEquals(450, result.getRevenuePerCraft(), "3 outputs at the 150c sell quote");
    }

    /**
     * DOMAIN_SPEC.md section 21: an output with no stored quote, or none in the selected mode, has a
     * known revenue of zero rather than an invented one.
     */
    @Test
    void anOutputWithNoUsableQuoteHasZeroRevenue() {
        Recipe recipe = recipe(1, 100, List.of());
        RecipeSimulationResult sim = simulation(1, 100, 1, 80);

        assertEquals(0, evaluate(recipe, sim, Map.of(), settings(false)).getRevenuePerCraft(),
                "no quote at all");
        assertEquals(0, evaluate(recipe, sim, Map.of(100, noQuote()), settings(false)).getRevenuePerCraft(),
                "a quote with neither unit");
        assertEquals(0, evaluate(recipe, sim, Map.of(100, quote(120, null)), settings(true)).getRevenuePerCraft(),
                "no sell unit for the selected listing-sell mode");
        assertEquals(80, evaluate(recipe, sim, Map.of(), settings(false)).getOpportunityCostPerCraft(),
                "the material given up is still reported");
    }

    /** No craft was simulated at all: there is no first craft to take an opportunity cost from. */
    @Test
    void aSimulationWithNoFirstCraftReportsNoOpportunityCost() {
        Recipe recipe = recipe(1, 100, List.of());
        RecipeSimulationResult sim = new RecipeSimulationResult(1, 100);

        CostEvaluationResult result = evaluate(recipe, sim, Map.of(100, quote(120, 150)), settings(false));

        assertEquals(120, result.getRevenuePerCraft());
        assertEquals(0, result.getOpportunityCostPerCraft());
    }

    /**
     * Both figures are per-craft (section 25): the craft count belongs to the planner's totals and
     * must not be multiplied into anything published here.
     */
    @Test
    void bothFiguresStayPerCraftWhateverTheCraftCount() {
        Recipe recipe = recipe(1, 100, 2, List.of());
        Map<Integer, PriceQuote> tp = Map.of(100, quote(120, 150));

        CostEvaluationResult oneCraft = evaluate(recipe, simulation(1, 100, 1, 50), tp, settings(false));
        CostEvaluationResult sevenCrafts = evaluate(recipe, simulation(1, 100, 7, 50), tp, settings(false));

        assertEquals(240, oneCraft.getRevenuePerCraft());
        assertEquals(240, sevenCrafts.getRevenuePerCraft(), "one execution's revenue, not seven");
        assertEquals(50, sevenCrafts.getOpportunityCostPerCraft(), "the first craft's cost, not seven");
    }

    /**
     * The revised contract itself (STORY-DOM-023 F001): the result carries the planner's two inputs
     * and nothing else. In particular no {@code profitPerCraft}/{@code totalProfit} - which were
     * computed before any Trading Post fee - and no buy cost, whose basis here
     * ({@code firstCraft.getBuyCostCopper()}) is not the one the planner's profit uses.
     */
    @Test
    void theResultPublishesTheTwoPlannerInputsAndNoProfit() {
        Set<String> published = Arrays.stream(CostEvaluationResult.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("getRevenuePerCraft", "getOpportunityCostPerCraft"), published);
    }
}
