package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-integer range, produced/surplus facts and Research Note container accounting; values hand-calculated. */
class ForgeProjectQuantityTest {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final int NOTE = 61, PRR = 104934, STONE = 20796, SHARD = 23, A = 5001, B = 5002, GOAL = 900;

    private static Recipe forge(int id, int out, int count, List<Ingredient> in, List<CurrencyRequirement> cur) {
        return new Recipe(-id, out, count, 0, "", in, cur, RecipeSource.MYSTIC_FORGE);
    }

    private static PriceQuote ask(int c) { return new PriceQuote(null, c); }

    private static void assertSafe(ForgeProjectPlanner.Node n) {
        for (long v : new long[]{n.needed(), n.fromOwned(), n.fromSurplus(), n.toCraft(), n.toAcquire(), n.crafts(), n.produced(), n.newSurplus()}) {
            assertTrue(Math.abs(v) <= MAX_SAFE, "unsafe node value " + v);
        }
        n.children().forEach(ForgeProjectQuantityTest::assertSafe);
    }

    @Test
    void vendorProducedRequirementIsAShoppingFactWithOnePaymentAndReusedSurplus() {
        // 5 Stones demanded by the first step, 3 more by the second: one vendor buy of 10 (1 Shard) covers both.
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(A, 1), new Ingredient(B, 1)), List.of());
        Recipe a = forge(2, A, 1, List.of(new Ingredient(STONE, 5)), List.of());
        Recipe b = forge(3, B, 1, List.of(new Ingredient(STONE, 3)), List.of());
        Recipe vendor = Recipe.vendor(-1_000_020796, STONE, 10, List.of(new CurrencyRequirement(SHARD, 1)), 0);
        var p = new ForgeProjectPlanner(List.of(t, a, b, vendor)).planOutput(t, 1, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertEquals(1, p.vendorPurchases().size());
        var v = p.vendorPurchases().get(0);
        assertEquals(STONE, v.itemId());
        assertEquals(1, v.purchases(), "one payment, the 2nd demand reuses the surplus");
        assertEquals(10, v.units());
        assertEquals(0, v.copper());
        assertEquals(List.of(new ForgeProjectPlanner.CurrencyLine(SHARD, 1L, null, null)), v.currencies(), "currency-only offer keeps its currency cost");
        assertEquals(1, p.currencies().get(0).needed());
        assertEquals(0, p.items().stream().filter(i -> i.itemId() == STONE).findFirst().orElseThrow().missing(),
                "vendor-made stock is not a trading-post leaf");
    }

    @Test
    void cashVendorPurchaseCarriesItsCopperAndOrdinaryLeavesAreUnaffected() {
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(19663, 2), new Ingredient(A, 1)), List.of());
        Recipe wine = Recipe.vendor(-1_000_019663, 19663, 1, List.of(), 2504);
        var p = new ForgeProjectPlanner(List.of(t, wine)).planOutput(t, 1, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(A, ask(100)));
        assertEquals(2, p.vendorPurchases().get(0).purchases());
        assertEquals(5008, p.vendorPurchases().get(0).copper());
        assertEquals(5108, p.knownCopper(), "vendor cash is counted once alongside the priced leaf");
        assertEquals(1, p.items().stream().filter(i -> i.itemId() == A).findFirst().orElseThrow().missing());
    }

    @Test
    void goldTotalAboveTheExactJsonRangeIsIncompleteNotAFakeNumber() {
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(A, 1)), List.of());
        var p = new ForgeProjectPlanner(List.of(t)).planOutput(t, 999_999_999L, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(A, ask(10_000_001)));
        assertFalse(p.complete());
        assertNotNull(p.incompleteReason());
        assertTrue(p.knownCopper() <= MAX_SAFE);
        assertNull(p.economics());
        assertEquals(999_999_999L, p.items().stream().filter(i -> i.itemId() == A).findFirst().orElseThrow().missing());
    }

    @Test
    void nestedMultiplicationBetweenTwoToFiftyThreeAndLongMaxIsRejected() {
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(A, 1000)), List.of());
        Recipe a = new Recipe(10, A, 1, 0, "", List.of(new Ingredient(B, 10_000)));
        var p = new ForgeProjectPlanner(List.of(t, a)).planOutput(t, 999_999_999L, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertFalse(p.complete());
        assertEquals("quantity exceeds the supported range", p.incompleteReason());
        p.items().forEach(i -> { assertTrue(i.needed() <= MAX_SAFE); assertTrue(i.missing() <= MAX_SAFE); });
        p.steps().forEach(s -> assertTrue(s.outputUnits() <= MAX_SAFE && s.crafts() <= MAX_SAFE));
        if (p.tree() != null) assertSafe(p.tree());
    }

    @Test
    void cumulativeSumPastTheRangeStopsButKeepsTheSafeSubtotal() {
        // two ingredients each 6e15 (< 2^53) whose sum exceeds it
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(A, 6_000_000), new Ingredient(B, 6_000_000)), List.of());
        var p = new ForgeProjectPlanner(List.of(t)).planOutput(t, 1_000_000_000L, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(A, ask(1), B, ask(1)));
        assertTrue(p.items().stream().allMatch(i -> i.needed() <= MAX_SAFE));
        assertTrue(p.knownCopper() <= MAX_SAFE);
        assertFalse(p.complete());
    }

    @Test
    void batchOutputExposesProducedAndSurplusAndLaterDemandReusesItWithoutPayingTwice() {
        // goal needs 5 stones now (vendor batch of 10 for 1 shard) and a second branch 4 more stones later
        Recipe batch = Recipe.vendor(-1_000_000_001, STONE, 10, List.of(new CurrencyRequirement(SHARD, 1)), 0);
        Recipe later = forge(3, A, 1, List.of(new Ingredient(STONE, 4)), List.of());
        Recipe t = forge(1, GOAL, 1, List.of(new Ingredient(STONE, 5), new Ingredient(A, 1)), List.of());
        var p = new ForgeProjectPlanner(List.of(t, later, batch)).plan(t, 1, Map.of(), Map.of(SHARD, 5L), Map.of());
        var stone = p.tree().children().get(0);
        assertEquals(5, stone.needed());
        assertEquals(5, stone.toCraft());
        assertEquals(10, stone.produced());
        assertEquals(5, stone.newSurplus());
        assertEquals(stone.needed(), stone.fromOwned() + stone.fromSurplus() + stone.toCraft() + stone.toAcquire());
        // the later 4 stones come from the 5 surplus: still exactly one purchase, one shard
        var laterStones = p.tree().children().get(1).children().get(0);
        assertEquals(4, laterStones.fromSurplus());
        assertEquals(0, laterStones.toCraft());
        assertEquals(1, p.currencies().stream().filter(c -> c.currencyId() == SHARD).findFirst().orElseThrow().needed());
    }

    // ---- Research Note containers ----
    private final Recipe notesGoal = forge(7, GOAL, 1, List.of(), List.of(new CurrencyRequirement(NOTE, 300)));
    private final Recipe prrA = new Recipe(15120, PRR, 1, 0, "Artificer", List.of(new Ingredient(A, 4)));
    private final Recipe prrB = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(new Ingredient(B, 3)));
    private final ForgeProjectPlanner.Context ctx = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), Set.of(15120, 15131));

    private ForgeProjectPlanner.Notes notes(ForgeProjectPlanner planner, Map<Integer, Long> owned, Map<Integer, Long> wallet,
                                            ForgeProjectPlanner.Context c) {
        return planner.plan(notesGoal, 1, true, ForgeProjectPlanner.Inventory.shared(owned), c, wallet,
                Map.of(A, ask(10), B, ask(100))).notes();
    }

    @Test
    void ownedContainersOnlyAreOpenedNotCrafted() {
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal, prrA, prrB)), Map.of(PRR, 3L), Map.of((int) NOTE, 0L), ctx);
        assertEquals(300, n.shortfall());
        assertEquals(2, n.containersNeeded());
        assertEquals(2, n.ownedContainers());
        assertEquals(300, n.ownedNotes());
        assertEquals(0, n.containersCrafted());
        assertEquals(0, n.producedNotes());
        assertEquals(0, n.acquireContainers());
        assertEquals(0, n.uncoveredNotes());
        assertEquals(0, n.surplus());
    }

    @Test
    void mixedOwnedAndCraftedAreReportedSeparately() {
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal, prrA, prrB)), Map.of(PRR, 1L), Map.of((int) NOTE, 0L), ctx);
        assertEquals(1, n.ownedContainers());
        assertEquals(150, n.ownedNotes());
        assertEquals(1, n.containersCrafted());
        assertEquals(150, n.producedNotes());
        assertEquals(0, n.uncoveredNotes());
        assertFalse(n.producerLocked());
    }

    @Test
    void absentProducerClaimsNeitherOutputNorSurplus() {
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal)), Map.of(PRR, 1L), Map.of((int) NOTE, 0L), ctx);
        assertEquals(1, n.ownedContainers());
        assertEquals(0, n.containersCrafted());
        assertEquals(0, n.producedNotes());
        assertEquals(1, n.acquireContainers());
        assertEquals(150, n.uncoveredNotes());
        assertEquals(0, n.surplus());
    }

    @Test
    void absentProducerAndNoOwnedContainerStillClaimsNoSurplus() {
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal)), Map.of(), Map.of((int) NOTE, 10L), ctx);
        assertEquals(290, n.shortfall());
        assertEquals(2, n.acquireContainers());
        assertEquals(290, n.uncoveredNotes());
        assertEquals(0, n.surplus());
    }

    @Test
    void lockedPlannedProducerIsFlaggedAsLockedPlannedWork() {
        var locked = new ForgeProjectPlanner.Context(List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), Set.of());
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal, prrA, prrB)), Map.of(), Map.of((int) NOTE, 0L), locked);
        assertEquals(2, n.containersCrafted());
        assertTrue(n.producerLocked());
    }

    @Test
    void unknownWalletKeepsOwnedContainersOutOfTheClaims() {
        var n = notes(new ForgeProjectPlanner(List.of(notesGoal, prrA, prrB)), Map.of(PRR, 3L), null, ctx);
        assertFalse(n.walletKnown());
        assertNull(n.shortfall());
        assertEquals(0, n.ownedContainers());
        assertEquals(0, n.surplus());
    }
}
