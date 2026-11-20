package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Research Notes inside one selected Forge project; every expected value is hand-calculated, not read from the code. */
class ForgeProjectNotesTest {
    private static final int NOTE = 61, PRR = 104934, A = 5001, B = 5002, SHARED = 5003, OUT1 = 1, OUT2 = 2;

    private static Recipe forge(int id, int out, List<Ingredient> in, int notes) {
        return new Recipe(-id, out, 1, 0, "", in, List.of(new CurrencyRequirement(NOTE, notes)), RecipeSource.MYSTIC_FORGE);
    }

    // two roots needing 130 and 80 Notes -> 210; variant A (id 15120) costs 4*SHARED, variant B (15131) 3*B
    private final Recipe root = new Recipe(-7, 900, 1, 0, "", List.of(new Ingredient(OUT1, 1), new Ingredient(OUT2, 1), new Ingredient(SHARED, 1)),
            List.of(), RecipeSource.MYSTIC_FORGE);
    private final Recipe branch1 = forge(8, OUT1, List.of(), 130);
    private final Recipe branch2 = forge(9, OUT2, List.of(), 80);
    private final Recipe prrA = new Recipe(15120, PRR, 1, 0, "Artificer", List.of(new Ingredient(SHARED, 4)));
    private final Recipe prrB = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(new Ingredient(B, 3)));
    private final ForgeProjectPlanner planner = new ForgeProjectPlanner(List.of(root, branch1, branch2, prrA, prrB));
    private final ForgeProjectPlanner.Context ctx = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15120, 15131));

    private static PriceQuote ask(int copper) { return new PriceQuote(null, copper); }

    private ForgeProjectPlanner.Project run(Map<Integer, Long> owned, Map<Integer, Long> wallet, Map<Integer, PriceQuote> prices) {
        return planner.plan(root, 1, true, ForgeProjectPlanner.Inventory.shared(owned), ctx, wallet, prices);
    }

    @Test
    void wallet60Needs150MissingOneBatchNoSurplusAndSharedStockIsConsumedOnce() {
        // root consumes 1 SHARED; owned 5 -> 4 left for the single PRR batch (4 SHARED); nothing to buy
        var p = run(Map.of(SHARED, 5L), Map.of((int) NOTE, 60L), Map.of(SHARED, ask(10), B, ask(100)));
        var n = p.notes();
        assertEquals(210, n.needed());
        assertEquals(60, n.walletUsed());
        assertEquals(150, n.shortfall());
        assertEquals(1, n.containersNeeded());
        assertEquals(1, n.containersCrafted());
        assertEquals(150, n.producedNotes());
        assertEquals(0, n.surplus());
        assertEquals(15120, n.producerRecipeId());
        assertEquals(0, p.items().stream().filter(i -> i.itemId() == SHARED).findFirst().orElseThrow().missing());
        assertEquals(15120, p.steps().stream().filter(s -> s.outputItemId() == PRR).findFirst().orElseThrow().recipeId());
        // wallet-used 60 Notes at variant A (4*10=40c per 150 Notes): ceil(60*40/150)=16; produced Notes are not deducted again
        assertEquals(16L, n.estimateCopper());
    }

    @Test
    void wallet10NeedsTwoBatchesAndKeeps100Surplus() {
        var p = run(Map.of(), Map.of((int) NOTE, 10L), Map.of(SHARED, ask(10), B, ask(100)));
        var n = p.notes();
        assertEquals(200, n.shortfall());
        assertEquals(2, n.containersCrafted());
        assertEquals(300, n.producedNotes());
        assertEquals(100, n.surplus());
        // 1 root + 2 batches * 4 = 9 SHARED to acquire
        assertEquals(9, p.items().stream().filter(i -> i.itemId() == SHARED).findFirst().orElseThrow().missing());
        assertEquals(3L, n.estimateCopper()); // 10 wallet Notes * 40c / 150 = 2.67 -> 3 (rounded up once)
    }

    @Test
    void unknownWalletPromisesNoProductionOrDeficit() {
        var p = run(Map.of(), null, Map.of(SHARED, ask(10)));
        var n = p.notes();
        assertFalse(n.walletKnown());
        assertNull(n.walletUsed());
        assertNull(n.shortfall());
        assertEquals(0, n.containersCrafted());
        assertNull(n.estimateCopper());
        assertTrue(p.steps().stream().noneMatch(s -> s.outputItemId() == PRR));
    }

    @Test
    void cheaperFullyPricedVariantIsChosenAndIncompleteQuoteBasisStaysUnavailable() {
        var cheaperB = run(Map.of(), Map.of((int) NOTE, 0L), Map.of(SHARED, ask(100), B, ask(10)));
        assertEquals(15131, cheaperB.notes().producerRecipeId());
        var none = run(Map.of(), Map.of((int) NOTE, 60L), Map.of());
        assertNull(none.notes().estimateCopper());
        assertNotNull(none.notes().estimateIssue());
    }

    @Test
    void producerTiersPreferPricedOverEligibleUnpricedAndFallBackToLowestIdWhenNothingIsPriced() {
        // only variant A (15120) is unlocked; its SHARED input is unpriced while B's input is priced
        var onlyA = new ForgeProjectPlanner.Context(
                List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15120));
        var p = planner.plan(root, 1, true, ForgeProjectPlanner.Inventory.shared(Map.of()), onlyA,
                Map.of((int) NOTE, 0L), Map.of(B, ask(10)));
        assertEquals(15131, p.notes().producerRecipeId(), "locked but fully priced beats eligible but unpriced");
        assertTrue(p.notes().producerLocked(), "the chosen producer stays visibly unavailable");
        // nothing priced: lowest recipe id regardless of which one is eligible
        var onlyB = new ForgeProjectPlanner.Context(
                List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15131));
        for (var c : List.of(onlyA, onlyB)) {
            var q = planner.plan(root, 1, true, ForgeProjectPlanner.Inventory.shared(Map.of()), c,
                    Map.of((int) NOTE, 0L), Map.of());
            assertEquals(15120, q.notes().producerRecipeId());
        }
    }

    @Test
    void lockedProducerIsStillPlannedButNotPerformable() {
        var locked = new ForgeProjectPlanner.Context(List.of(), java.util.Set.of());
        var p = planner.plan(root, 1, true, ForgeProjectPlanner.Inventory.shared(Map.of()), locked,
                Map.of((int) NOTE, 0L), Map.of(SHARED, ask(10)));
        var prr = p.tree().children().stream().filter(c -> c.itemId() == PRR).findFirst().orElseThrow();
        assertFalse(prr.performable());
        assertEquals(210, p.notes().shortfall());
    }

    @Test
    void pathReachingNotesRequestsBothVariantsInputsRegardlessOfStockOrUnlock() {
        var ids = planner.pathItemIds(root);
        assertTrue(ids.containsAll(java.util.Set.of(SHARED, B, PRR)), ids.toString());
        assertFalse(planner.pathItemIds(prrA).contains(B), "a path without Notes demand does not pull the other variant");
    }
}
