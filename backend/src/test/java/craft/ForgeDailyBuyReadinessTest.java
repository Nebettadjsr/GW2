package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Readiness follows the project's Daily Buy/Craft policy: a bought daily output counts only owned copies. */
class ForgeDailyBuyReadinessTest {
    private static final int GOAL = 900, MITH = 46742, ING = 5001, PLAIN = 5100, PLAIN_ING = 5101;
    private final Recipe root = new Recipe(-1, GOAL, 1, 0, "", List.of(new Ingredient(MITH, 2), new Ingredient(PLAIN, 1)),
            List.of(), RecipeSource.MYSTIC_FORGE);
    private final Recipe mithProducer = new Recipe(7310, MITH, 1, 0, "", List.of(new Ingredient(ING, 3)));
    private final Recipe plainProducer = new Recipe(7311, PLAIN, 1, 0, "", List.of(new Ingredient(PLAIN_ING, 2)));
    private final ForgeProjectPlanner planner = LegacyForge.planner(List.of(root, mithProducer, plainProducer));

    private record R(ForgeProjectPlanner.Project project, double percent) { }

    private R run(ForgeSettings.DailyMode daily, Map<Integer, Integer> owned) {
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var s = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false, daily, false, ForgeSettings.ReduceMode.NO);
        var p = planner.plan(root, 1, false, inv, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(), s);
        return new R(p, planner.materialProgress(root, 1, inv, ForgeProjectPlanner.Context.NONE, Map.of(), p).percent());
    }

    private static long missing(ForgeProjectPlanner.Project p, int item) {
        return p.items().stream().filter(l -> l.itemId() == item).mapToLong(ForgeProjectPlanner.ItemLine::missing).sum();
    }

    @Test
    void buyIgnoresProducerIngredientsWhileCraftCountsThem() {
        var all = Map.of(ING, 6, PLAIN_ING, 2); // every ingredient owned, no Mithrillium
        var buy = run(ForgeSettings.DailyMode.BUY, all);
        assertEquals(2, missing(buy.project(), MITH), "plan lists Mithrillium to buy");
        assertTrue(buy.percent() < 100.0, "readiness must not claim ready");
        assertEquals(50.0, buy.percent(), "mithril share 0, plain share 1 -> half");
        var craft = run(ForgeSettings.DailyMode.CRAFT, all);
        assertEquals(0, missing(craft.project(), MITH));
        assertEquals(100.0, craft.percent());
    }

    @Test
    void partialAndFullyOwnedDailyOutputCountNormally() {
        var partial = run(ForgeSettings.DailyMode.BUY, Map.of(MITH, 1, ING, 6, PLAIN_ING, 2));
        assertEquals(1, missing(partial.project(), MITH));
        assertEquals(75.0, partial.percent(), "half the Mithrillium owned (0.5), plain ready (1) -> 75%");
        var full = run(ForgeSettings.DailyMode.BUY, Map.of(MITH, 2, PLAIN_ING, 2));
        assertEquals(0, missing(full.project(), MITH));
        assertEquals(100.0, full.percent());
    }

    @Test
    void nonDailyRecipeIsUnaffectedByDailyPolicy() {
        var owned = Map.of(MITH, 2, PLAIN_ING, 1);
        var buy = run(ForgeSettings.DailyMode.BUY, owned);
        var craft = run(ForgeSettings.DailyMode.CRAFT, owned);
        assertEquals(1, missing(buy.project(), PLAIN_ING));
        assertEquals(craft.percent(), buy.percent());
        assertEquals(75.0, buy.percent(), "plain ingredient half owned -> (1 + 0.5)/2");
    }

    @Test
    void recipeTreeStopsProgressBelowBoughtDailyProducer() {
        var inv = new ForgeProjectPlanner.Inventory(Map.of(ING, 6, PLAIN_ING, 2), Map.of(), Map.of());
        var ctx = ForgeProjectPlanner.Context.NONE;
        var buy = planner.recipeTree(root, 1, inv, ctx, Map.of(), true, ForgeSettings.ReduceMode.NO, ForgeSettings.DailyMode.BUY);
        var craft = planner.recipeTree(root, 1, inv, ctx, Map.of(), true, ForgeSettings.ReduceMode.NO, ForgeSettings.DailyMode.CRAFT);
        var buyMith = buy.children().stream().filter(c -> c.itemId() == MITH).findFirst().orElseThrow();
        var craftMith = craft.children().stream().filter(c -> c.itemId() == MITH).findFirst().orElseThrow();
        assertEquals(1, buyMith.children().size(), "reference rows are preserved");
        assertEquals(0, buyMith.children().getFirst().acquired(), "no stock credited below the bought daily output");
        assertEquals(0, buyMith.progressPercent());
        assertTrue(craftMith.children().getFirst().acquired() > 0, "Craft still credits the ingredients");
    }
}
