package craft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executors;
import static craft.CraftTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReusableSimulationStateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void wholeRecipeRollbackRestoresCommittedCraftsAndEveryInventoryPool() {
        var state = PlanState.reusable(Map.of(1, 12), Map.of(2, 5), Map.of("A", Map.of(3, 9)));
        state.consumeInventoryWithBinding(1, 7, "A");
        state.consumeInventoryWithBinding(2, 4, "A");
        state.consumeInventoryWithBinding(3, 6, "A");
        state.setDailyLeft(46742, 0);
        state.beginVisiting(100);
        state.addBuyCost(37);
        state.commitTo(0);
        state.rollbackTo(0);
        assertEquals(Map.of(1, 12), state.inventory);
        assertEquals(Map.of(2, 5), state.boundInventory);
        assertEquals(Map.of("A", Map.of(3, 9)), state.characterBoundInventory);
        assertTrue(state.dailyLeft.isEmpty());
        assertTrue(state.visiting.isEmpty());
        assertEquals(0, state.buyCostCopper);
        assertEquals(0, state.consumedSellableQuantity(1));
    }

    @Test void matchesFreshStateAcrossSettingsAlternativeRecipesCyclesBindingAndDailyLimits() {
        for (int seed = 0; seed < 40; seed++) {
            Fixture f = fixture(seed);
            for (int settings = 0; settings < 8; settings++) {
                var input = new CraftingSettings((settings & 1) != 0, (settings & 2) != 0,
                        200, false, true, (settings & 4) != 0);
                var ordinary = calculate(new CraftingPlanner(), f, input);
                var reused = calculate(new CraftingPlanner().withReusableSimulationState(), f, input);
                assertEquals(JSON.valueToTree(ordinary), JSON.valueToTree(reused), "seed=" + seed + ", settings=" + settings);
            }
        }
    }

    @Test void independentConcurrentAccountsCanReuseTheSamePlannerWithoutSharingWorkerState() throws Exception {
        var planner = new CraftingPlanner().withReusableSimulationState();
        var input = new CraftingSettings(true, true, 200, false, true, true);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int account = 0; account < 24; account++) {
                int seed = 1000 + account;
                futures.add(executor.submit(() -> {
                    Fixture f = fixture(seed);
                    var expected = JSON.valueToTree(calculate(new CraftingPlanner(), f, input));
                    assertEquals(expected, JSON.valueToTree(calculate(planner, f, input)), "account fixture " + seed);
                }));
            }
            for (var future : futures) future.get();
        }
    }

    private static Map<Integer, CraftResult> calculate(CraftingPlanner planner, Fixture f, CraftingSettings settings) {
        var sellableBefore = Map.copyOf(f.sellable());
        var boundBefore = Map.copyOf(f.bound());
        var result = planner.withVendorValues(Map.of(3, 4)).evaluateAllCoordinated(f.recipes(), f.sellable(), f.bound(),
                f.characterBound(), List.of(profile("A", "Artificer", 400), profile("B", "Artificer", 400)),
                f.prices(), settings, f.allowed());
        assertEquals(sellableBefore, f.sellable(), "No caller inventory mutation");
        assertEquals(boundBefore, f.bound(), "No caller bound-inventory mutation");
        return result;
    }

    private record Fixture(List<Recipe> recipes, Map<Integer,Integer> sellable, Map<Integer,Integer> bound,
                           Map<String, Map<Integer,Integer>> characterBound, Map<Integer,PriceQuote> prices,
                           Set<Integer> allowed) { }

    private static Fixture fixture(int seed) {
        var random = new Random(seed);
        var recipes = new ArrayList<Recipe>();
        var sellable = new HashMap<Integer,Integer>();
        var bound = new HashMap<Integer,Integer>();
        var prices = new HashMap<Integer,PriceQuote>();
        var allowed = new HashSet<Integer>();
        for (int item = 1; item <= 4; item++) {
            sellable.put(item, 2 + random.nextInt(20));
            bound.put(item, random.nextInt(10));
            if (item != 3) prices.put(item, quote(2 + random.nextInt(9), 3 + random.nextInt(9)));
        }
        for (int index = 0; index < 22; index++) {
            int output = 1000 + index;
            int ingredient = index < 4 ? 1 + random.nextInt(4) : 1000 + random.nextInt(index);
            recipes.add(recipe(100 + index, output, 1 + random.nextInt(3), List.of(ingredient(ingredient, 1 + random.nextInt(3)))));
            allowed.add(100 + index);
            prices.put(output, quote(10 + random.nextInt(40), 12 + random.nextInt(40)));
            if (index % 7 == 0) {
                recipes.add(recipe(500 + index, output, List.of(ingredient(4, 2))));
                allowed.add(500 + index);
            }
        }
        recipes.add(recipe(800, 46742, List.of(ingredient(1, 2))));
        recipes.add(recipe(801, 2001, List.of(ingredient(46742, 1))));
        recipes.add(recipe(802, 2002, List.of(ingredient(2002, 1))));
        allowed.addAll(Set.of(800, 801, 802));
        prices.put(46742, quote(7, 10));
        prices.put(2001, quote(50, 60));
        prices.put(2002, quote(20, 30));
        return new Fixture(recipes, sellable, bound, Map.of("A", Map.of(2, 7), "B", Map.of(1, 11)), prices, allowed);
    }
}
