package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exact expected-total admission and real-owner binding of Mystic Forge steps (independent hand-computed figures). */
class MysticForgeBoundaryTest {

    private static final int OUT = 800_001, IN = 800_002;

    private static CraftingSettings settings(boolean forge) {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(forge);
    }

    @Test
    void exactExpectedTotalRejectsOnlyTheOverflowingThirdBatch() {
        Recipe r = Recipe.forgeWithExpectedYield(-777, OUT, ExpectedYield.of("1.01", 1, 2), List.of(new Ingredient(IN, 1)));
        // Per-craft floor(708740478 * 1.01) = 715827882; 3 x that = 2147483646 fits, but the exact
        // aggregate floor(708740478 * 3.03) = 2147483648 exceeds int max. Two crafts: floor(*2.02) = 1431655765.
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(r), Map.of(IN, 3), Map.of(),
                Map.of(OUT, new PriceQuote(708_740_478, null)), settings(true), Set.of()).get(-777);
        assertEquals(2, res.craftableCount);
        assertEquals(BlockedReason.VALUE_RANGE, res.blockedReason);
        assertEquals(1_431_655_765, res.totalSellValueCopper);
        // 15% fee of 1431655765 = 214748364.75 -> rounded up once
        assertEquals(1_431_655_765L - 214_748_365L, res.totalProfitCopper);
        assertTrue(res.estimated);
        assertEquals(new java.math.BigDecimal("2.02"), res.expectedSellableUnitsTotal);
    }

    private static Recipe forgeNeedingOne() {
        return new Recipe(-778, OUT, 1, 0, "", List.of(new Ingredient(IN, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static CraftResult coordinated(boolean forge, List<CharacterCraftingProfile> roster,
                                           Map<String, Map<Integer, Integer>> bound, Map<Integer, Integer> shared) {
        return new CraftingPlanner().evaluateAllCoordinated(List.of(forgeNeedingOne()), shared, Map.of(), bound, roster,
                Map.of(OUT, new PriceQuote(1000, null)), settings(forge), Set.of(-778)).get(-778);
    }

    private static CharacterCraftingProfile untrained(String name) {
        return new CharacterCraftingProfile(name, Map.of());
    }

    @Test
    void untrainedRealOwnerUsesItsOwnSoulboundMaterial() {
        CraftResult res = coordinated(true, List.of(untrained("Solo")),
                Map.of("Solo", Map.of(IN, 1)), Map.of());
        assertEquals(1, res.craftableCount);
        assertEquals(BlockedReason.BUYING_DISABLED, res.blockedReason, "the second craft has no material");
        assertEquals(850, res.totalProfitCopper);
    }

    @Test
    void sharedStockStillWorksWithoutAnyCharacterBinding() {
        CraftResult res = coordinated(true, List.of(untrained("Solo")), Map.of(), Map.of(IN, 2));
        assertEquals(2, res.craftableCount);
    }

    @Test
    void soulboundMaterialOfTwoOwnersIsNeverPooledInOneStep() {
        Recipe two = new Recipe(-779, OUT, 1, 0, "", List.of(new Ingredient(IN, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        CraftResult res = new CraftingPlanner().evaluateAllCoordinated(List.of(two), Map.of(), Map.of(),
                Map.of("A", Map.of(IN, 1), "B", Map.of(IN, 1)), List.of(untrained("A"), untrained("B")),
                Map.of(OUT, new PriceQuote(1000, null)), settings(true), Set.of(-779)).get(-779);
        assertEquals(0, res.craftableCount);
        // one owner holding both units can perform it
        CraftResult one = new CraftingPlanner().evaluateAllCoordinated(List.of(two), Map.of(), Map.of(),
                Map.of("A", Map.of(IN, 2), "B", Map.of()), List.of(untrained("A"), untrained("B")),
                Map.of(OUT, new PriceQuote(1000, null)), settings(true), Set.of(-779)).get(-779);
        assertEquals(1, one.craftableCount);
    }

    @Test
    void toggleOffIgnoresBoundOwnerStock() {
        assertEquals(0, coordinated(false, List.of(untrained("Solo")),
                Map.of("Solo", Map.of(IN, 1)), Map.of()).craftableCount);
    }
}
