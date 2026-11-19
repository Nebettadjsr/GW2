package craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Initial owner index in PlanState and its use by Forge owner trials (STORY-FORGE-001 performance pass). */
class ForgeOwnerIndexTest {
    private static final int OUT = 831_000, A = 831_001, B = 831_002, UNRELATED = 831_900;

    private static PlanState state(Map<String, Map<Integer, Integer>> carried) {
        return new PlanState(Map.of(), Map.of(), carried);
    }

    @Test
    void indexListsInitialCarriedOwnersAndIgnoresBank() {
        PlanState s = state(Map.of("Zed", Map.of(A, 1), "Amy", Map.of(A, 2, B, 1),
                PlanState.bankOwnerKey("Amy"), Map.of(A, 5)));
        assertEquals(Set.of("Zed", "Amy"), Set.copyOf(s.initialCarriedOwnersOf(A)));
        assertEquals(List.of("Amy"), s.initialCarriedOwnersOf(B));
        assertTrue(s.initialCarriedOwnersOf(UNRELATED).isEmpty());
    }

    @Test
    void indexStaysSupersetAfterConsumptionAndRollbackAndIsSharedByCopies() {
        PlanState s = state(Map.of("Amy", Map.of(A, 1)));
        int mark = s.mark();
        s.consumeInventoryWithBinding(A, 1, PlanState.forgeAssignee("Amy"));
        assertEquals(0, s.carriedQuantity("Amy", A), "current quantity reflects consumption");
        assertEquals(List.of("Amy"), s.initialCarriedOwnersOf(A), "initial index is not shrunk");
        PlanState copy = new PlanState(s);
        assertEquals(List.of("Amy"), copy.initialCarriedOwnersOf(A));
        s.rollbackTo(mark);
        assertEquals(1, s.carriedQuantity("Amy", A), "rollback restores the owner");
        assertEquals(0, copy.carriedQuantity("Amy", A), "copies stay independent");
    }

    @Test
    void bankStockOfAnyOwnerIsStillPooledInSortedOrderForForgeAssignee() {
        PlanState s = state(Map.of(PlanState.bankOwnerKey("B"), Map.of(A, 1), PlanState.bankOwnerKey("A"), Map.of(A, 1)));
        var used = s.consumeInventoryWithBinding(A, 2, PlanState.forgeAssignee(null));
        assertEquals(2, used.usedBound());
        assertEquals(0, s.characterBoundInventory.get(PlanState.bankOwnerKey("A")).getOrDefault(A, 0));
    }

    private static CraftResult run(Map<String, Map<Integer, Integer>> carried, boolean buy) {
        Recipe r = new Recipe(-831, OUT, 1, 0, "", List.of(new Ingredient(A, 1), new Ingredient(B, 1)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        var settings = new CraftingSettings(true, buy, buy ? 1_000_000 : 0, false, false, false).withMysticForge(true);
        return new CraftingPlanner().evaluateAllCoordinated(List.of(r), Map.of(), Map.of(), carried,
                List.of(new CharacterCraftingProfile("Crafter", Map.of())),
                Map.of(OUT, new PriceQuote(5_000, 5_000), A, new PriceQuote(100, 100), B, new PriceQuote(50, 50)),
                settings, Set.of(r.recipeId)).get(r.recipeId);
    }

    @Test
    void manyUnrelatedOwnersDoNotChangeResultsAndRelevantOwnerIsStillUsed() {
        Map<String, Map<Integer, Integer>> noise = new HashMap<>();
        for (int i = 0; i < 300; i++) noise.put("Noise" + i, Map.of(UNRELATED + i, 3));
        noise.put(PlanState.bankOwnerKey("Nobody"), Map.of(UNRELATED, 9));
        CraftResult base = run(Map.of(), true);
        CraftResult withNoise = run(noise, true);
        assertEquals(base.craftableCount, withNoise.craftableCount);
        assertEquals(base.missingToBuy, withNoise.missingToBuy);
        assertEquals(base.profitCopper, withNoise.profitCopper);

        Map<String, Map<Integer, Integer>> withOwner = new HashMap<>(noise);
        withOwner.put("Holder", Map.of(A, 1));
        CraftResult owned = run(withOwner, true);
        assertFalse(owned.missingToBuyOne.containsKey(A), "relevant carried owner still avoids buying A");
        assertEquals(1, owned.missingToBuyOne.get(B));
    }
}
