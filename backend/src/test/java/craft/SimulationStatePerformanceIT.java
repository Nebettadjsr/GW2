package craft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static craft.CraftTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit local paired measurement; no timing threshold or CI performance gate. */
class SimulationStatePerformanceIT {
    @Test void compareFreshAndReusedStatesOnCatalog() throws Exception {
        int count = Integer.getInteger("gw2.benchmark.recipes", 13000);
        var recipes = new ArrayList<Recipe>();
        var owned = new HashMap<Integer, Integer>();
        var prices = new HashMap<Integer, PriceQuote>();
        var allowed = new HashSet<Integer>();
        for (int id = 1; id <= 400; id++) {
            owned.put(id, 200);
            prices.put(id, quote(3, 5));
        }
        for (int index = 0; index < count; index++) {
            int output = 10000 + index;
            int input = index < 400 ? 1 + index : 10000 + (index - 400) / 2;
            recipes.add(recipe(index + 1, output, 1 + index % 6, List.of(ingredient(input, 5))));
            prices.put(output, quote(25, 30));
            allowed.add(index + 1);
        }
        var settings = new CraftingSettings(true, true, 200, false, false, true);
        var roster = List.of(profile("A", "Artificer", 400), profile("B", "Artificer", 400));
        var mapper = new ObjectMapper();
        for (int run = 0; run < 6; run++) {
            var results = new HashMap<Boolean, Map<Integer, CraftResult>>();
            var times = new HashMap<Boolean, Long>();
            for (boolean reuse : run % 2 == 0 ? List.of(false, true) : List.of(true, false)) {
                long start = System.nanoTime();
                results.put(reuse, new CraftingPlanner(reuse).evaluateAllCoordinated(recipes, owned,
                        Map.of(), Map.of(), roster, prices, settings, allowed));
                times.put(reuse, (System.nanoTime() - start) / 1_000_000);
            }
            assertEquals(mapper.valueToTree(results.get(false)), mapper.valueToTree(results.get(true)));
            System.out.printf("STATE_PAIR run=%d recipes=%d control_ms=%d reuse_ms=%d%n",
                    run, count, times.get(false), times.get(true));
        }
    }
}
