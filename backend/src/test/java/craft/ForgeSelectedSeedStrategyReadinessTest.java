package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Readiness follows the plan's per-occurrence seed strategy: purchased seed (goal-first owned unit) vs diverted owned seed. */
class ForgeSelectedSeedStrategyReadinessTest {
    private static final int ROOT = 1, SEED = 2, ORE = 3;
    private final Recipe promo = Recipe.forgeWithExpectedYield(-2, SEED, ExpectedYield.of("20", 10, 30),
            List.of(new Ingredient(ORE, 2), new Ingredient(SEED, 1)));
    private final Recipe root = new Recipe(-1, ROOT, 1, 0, "", List.of(new Ingredient(SEED, 11)), List.of(), RecipeSource.MYSTIC_FORGE);
    private static final ForgeSettings SETTINGS = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false,
            ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.NO);

    @Test
    void purchasedSeedStrategyIsNotReportedReady() {
        var prices = Map.of(SEED, new PriceQuote(100, 100), ORE, new PriceQuote(100, 100));
        var planner = new ForgeProjectPlanner(List.of(promo, root), SETTINGS);
        var inv = new ForgeProjectPlanner.Inventory(Map.of(SEED, 1, ORE, 4), Map.of(), Map.of());
        var project = planner.plan(root, 1, false, inv, ForgeProjectPlanner.Context.NONE, null, prices, SETTINGS);
        assertTrue(project.complete());
        assertEquals(1, project.items().stream().filter(l -> l.itemId() == SEED).findFirst().orElseThrow().missing());
        assertEquals(1, project.steps().stream().filter(s -> s.recipeId() == promo.recipeId).findFirst().orElseThrow().crafts());
        // 1 owned of 11 goal units; the 10 new units: Ore ready (1), purchased seed not owned (0) -> 1/11 + 10/11 * 1/2
        assertEquals(54.5, planner.materialProgress(root, 1, inv, ForgeProjectPlanner.Context.NONE, null, project).percent());
    }
}
