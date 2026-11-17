import craft.*;
import java.util.*;

/** Supplemental pure-domain sensitivity check, not an application/HTTP benchmark. */
class EmptyInventoryTradeoff {
    static final List<Recipe> RECIPES = new ArrayList<>();
    static final Map<Integer, PriceQuote> PRICES = new HashMap<>();
    static final Set<Integer> ALLOWED = new HashSet<>();
    static final List<CharacterCraftingProfile> ROSTER = List.of(new CharacterCraftingProfile("A", Map.of("Chef", 400)));
    static final CraftingSettings SETTINGS = new CraftingSettings(true, true, 1000, false, true, false);
    static {
        PRICES.put(1, new PriceQuote(5, 10));
        for (int i = 0; i < 2000; i++) {
            RECIPES.add(new Recipe(20000 + i, 10000 + i, 1, 0, "Chef", List.of(new Ingredient(1, 1))));
            ALLOWED.add(20000 + i);
            PRICES.put(10000 + i, new PriceQuote(30, 40));
        }
    }
    record Observation(double millis, long checksum) {}
    static Observation run(boolean reuse) {
        var planner = new CraftingPlanner();
        if (reuse) planner = planner.withReusableSimulationState();
        long start = System.nanoTime();
        var result = planner.evaluateAllCoordinated(RECIPES, Map.of(), Map.of(), Map.of(), ROSTER, PRICES, SETTINGS, ALLOWED);
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        long checksum = 0;
        for (var entry : result.entrySet()) {
            CraftResult row = entry.getValue();
            if (row.craftableCount <= 0) throw new AssertionError("Must exercise buying/crafting");
            checksum += (long) entry.getKey() * (row.craftableCount + row.buyCostCopper + row.totalProfitCopper);
        }
        return new Observation(elapsed, checksum);
    }
    public static void main(String[] args) {
        for (int warmup = 0; warmup < 3; warmup++) { run(false); run(true); }
        System.out.println("{\"scope\":\"supplemental pure domain, 2000 one-ingredient recipes, empty inventory, buying enabled, budget 1000; no HTTP/SQL\",\"pairs\":[");
        for (int pair = 1; pair <= 5; pair++) {
            Observation control, candidate;
            if (pair % 2 == 1) { control = run(false); candidate = run(true); }
            else { candidate = run(true); control = run(false); }
            if (control.checksum() != candidate.checksum()) throw new AssertionError("Results differ");
            System.out.printf(java.util.Locale.ROOT, "%s{\"pair\":%d,\"controlMs\":%.3f,\"candidateMs\":%.3f,\"checksum\":%d}", pair == 1 ? "" : ",\n", pair, control.millis(), candidate.millis(), control.checksum());
        }
        System.out.println("\n]}");
    }
}
