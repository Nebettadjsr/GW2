package craft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** Many-character Forge chains: irrelevant carried stock must not multiply speculative owner trials. */
class ForgeRosterPruningTest {
    private static final int OUT = 820_000, IN = 820_001, JUNK = 820_002, DEPTH = 6;

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
    }

    /** Chain of Forge steps, each needing 1 IN plus the previous step's output. */
    private static List<Recipe> chain() {
        List<Recipe> rs = new ArrayList<>();
        for (int i = 0; i < DEPTH; i++) {
            List<Ingredient> ing = new ArrayList<>(List.of(new Ingredient(IN, 1)));
            if (i > 0) ing.add(new Ingredient(OUT + i - 1 + 100, 1));
            rs.add(new Recipe(-100 - i, OUT + i + 100, 1, 0, "", ing, List.of(), RecipeSource.MYSTIC_FORGE));
        }
        return rs;
    }

    private static List<CharacterCraftingProfile> roster(int n) {
        List<CharacterCraftingProfile> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(new CharacterCraftingProfile("C" + i, Map.of()));
        return r;
    }

    private static CraftResult run(int chars, Map<String, Map<Integer, Integer>> bound, Map<Integer, Integer> shared) {
        List<Recipe> rs = chain();
        int top = rs.getLast().recipeId;
        return new CraftingPlanner().evaluateAllCoordinated(rs, shared, Map.of(), bound, roster(chars),
                Map.of(OUT + DEPTH - 1 + 100, new PriceQuote(100_000, null),
                        IN, new PriceQuote(10, 20)), settings(), Set.of(top)).get(top);
    }

    @Test
    void irrelevantCarriedStockOfLargeRosterFinishesQuicklyWithSameResult() {
        Map<String, Map<Integer, Integer>> junk = new HashMap<>();
        for (int i = 0; i < 40; i++) junk.put("C" + i, Map.of(JUNK, 5));
        CraftResult many = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> run(40, junk, Map.of()));
        CraftResult one = run(1, Map.of(), Map.of());
        assertEquals(one.craftableCount, many.craftableCount);
        assertEquals(one.missingToBuy, many.missingToBuy);
    }

    @Test
    void relevantCarriedStockOfOneCharacterStillUsedAmongMany() {
        Map<String, Map<Integer, Integer>> bound = new HashMap<>();
        bound.put("C7", Map.of(IN, DEPTH));
        CraftResult res = run(12, bound, Map.of());
        assertTrue(res.missingToBuy.isEmpty() || res.missingToBuy.values().stream().noneMatch(v -> v != null && v.toString().contains("820001")),
                "the owner's carried IN stock supplies the chain without buying");
        assertEquals(1, res.craftableCount);
    }

    @Test
    void interruptedThreadStopsCalculation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> run(3, Map.of(), Map.of()));
        } finally {
            Thread.interrupted();
        }
    }
}
