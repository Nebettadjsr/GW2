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

    /** Protected vs disabled planner comparison on the same in-memory fixture (no database, no threshold). */
    @Test void compareProtectedAndDisabledMaterialProtection() throws Exception {
        int count = Integer.getInteger("gw2.benchmark.recipes", 13000);
        int warmups = 2, runs = 6;
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
        var protectedIds = new ArrayList<Integer>();
        for (int id = 1; id <= 100; id++) protectedIds.add(id);   // nonempty: 100 of 400 owned raw items
        var disabled = new CraftingSettings(true, true, 200, false, false, true);
        var guarded = new CraftingSettings(true, true, 200, false, false, true, true, protectedIds);
        var roster = List.of(profile("A", "Artificer", 400), profile("B", "Artificer", 400));
        var ownedBefore = new HashMap<>(owned);
        var mapper = new ObjectMapper();
        System.out.printf("PROTECTION_FIXTURE recipes=%d ownedItems=%d protectedItems=%d warmups=%d runs=%d "
                        + "java=%s cpus=%d os=%s/%s db=none (in-memory)%n",
                count, owned.size(), protectedIds.size(), warmups, runs, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"),
                System.getProperty("os.arch"));
        for (int run = -warmups; run < runs; run++) {
            long t0 = System.nanoTime();
            new CraftingPlanner(true).evaluateAllCoordinated(recipes, owned, Map.of(), Map.of(), roster,
                    prices, disabled, allowed);
            long disabledMs = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            var reused = new CraftingPlanner(true).evaluateAllCoordinated(recipes, owned, Map.of(), Map.of(),
                    roster, prices, guarded, allowed);
            long reuseMs = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            var fresh = new CraftingPlanner(false).evaluateAllCoordinated(recipes, owned, Map.of(), Map.of(),
                    roster, prices, guarded, allowed);
            long freshMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(mapper.valueToTree(fresh), mapper.valueToTree(reused),
                    "protected fresh-state and reusable-state results must be equal");
            assertEquals(ownedBefore, owned, "source inventory must not be mutated");
            if (run >= 0) {
                System.out.printf("PROTECTION_PAIR run=%d disabled_reuse_ms=%d protected_reuse_ms=%d "
                        + "protected_fresh_ms=%d%n", run, disabledMs, reuseMs, freshMs);
            }
        }
    }
}
