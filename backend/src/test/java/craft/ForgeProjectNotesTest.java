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
    private final ForgeProjectPlanner planner = LegacyForge.planner(List.of(root, branch1, branch2, prrA, prrB));
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

    private ForgeProjectPlanner.Project bigNotes(long need, long wallet) {
        Recipe big = new Recipe(-11, 901, 1, 0, "", List.of(), List.of(new CurrencyRequirement(NOTE, (int) 1)), RecipeSource.MYSTIC_FORGE);
        return planner.plan(big, need, true, ForgeProjectPlanner.Inventory.shared(Map.of()), ctx,
                Map.of((int) NOTE, wallet), Map.of(SHARED, ask(150)));
    }

    @Test
    void safeAggregateAboveIntMaxStaysExact() {
        // 1,000,000,000 wallet Notes at variant A: 4*150=600c per 150 Notes -> exactly 4,000,000,000c
        var p = bigNotes(1_000_000_000L, 1_000_000_000L);
        assertTrue(p.complete());
        assertEquals(4_000_000_000L, p.notes().estimateCopper());
        assertNull(p.notes().estimateIssue());
        assertEquals(0, p.notes().containersNeeded(), "wallet-used Notes are not produced/charged again");
    }

    @Test
    void forgeRangeOverflowIsTruthfullyIncompleteNotWrappedOrZero() {
        var p = bigNotes(ForgeProjectPlanner.MAX_SAFE, ForgeProjectPlanner.MAX_SAFE);
        assertFalse(p.complete());
        assertNotNull(p.incompleteReason());
        assertNull(p.notes().estimateCopper());
        assertEquals("estimate exceeds the supported range", p.notes().estimateIssue());
        assertTrue(p.guaranteedOutput() < 0);
    }

    @Test
    void rangeStopWithRemainingDeficitPlansNoProductionAndDoesNotThrow() {
        // need 1,000,000,002 Notes, wallet 1,000,000,000: valuation at 2,000,000,000c per 150 Notes exceeds MAX_SAFE
        Recipe big = new Recipe(-11, 901, 1, 0, "", List.of(), List.of(new CurrencyRequirement(NOTE, 1)), RecipeSource.MYSTIC_FORGE);
        var p = planner.plan(big, 1_000_000_002L, true, ForgeProjectPlanner.Inventory.shared(Map.of()), ctx,
                Map.of((int) NOTE, 1_000_000_000L), Map.of(SHARED, ask(500_000_000)));
        var n = p.notes();
        assertFalse(p.complete());
        assertNotNull(p.incompleteReason());
        assertTrue(p.guaranteedOutput() < 0);
        assertNull(n.estimateCopper());
        assertEquals("estimate exceeds the supported range", n.estimateIssue());
        assertEquals(1_000_000_002L, n.needed());
        assertEquals(1_000_000_000L, n.walletUsed());
        assertEquals(2L, n.shortfall());
        assertEquals(0, n.containersCrafted());
        assertEquals(0, n.producedNotes());
        assertEquals(0, n.surplus());
        assertEquals(2L, n.uncoveredNotes());
    }

    @Test
    void noteSchedulingChildIsOrderedWorkButNeverAnIngredientOfTheRootInstruction() {
        // wallet 10 -> two Peer Reviewed Research crafts are scheduled before the Note-spending branches.
        var p = run(Map.of(), Map.of((int) NOTE, 10L), Map.of(SHARED, ask(10), B, ask(100)));
        var instructions = ForgeCraftingInstructions.of(p);
        var prr = instructions.stream().filter(i -> i.operation().itemId() == PRR).toList();
        assertEquals(1, prr.size(), "the Note production stays one ordered instruction");
        assertEquals(2, prr.get(0).crafts());
        assertEquals(java.util.Map.of(SHARED, 8L), prr.get(0).ingredients().stream()
                .collect(java.util.stream.Collectors.toMap(ForgeCraftingInstructions.Input::itemId, ForgeCraftingInstructions.Input::quantity)));
        var rootInstruction = instructions.get(instructions.size() - 1);
        assertTrue(rootInstruction.root());
        assertTrue(rootInstruction.ingredients().stream().noneMatch(in -> in.itemId() == PRR),
                "Peer Reviewed Research is scheduled before the root, it is not a root recipe ingredient");
        assertTrue(rootInstruction.ingredients().stream().anyMatch(in -> in.itemId() == SHARED), "real root inputs stay");
        int prrAt = -1, rootAt = instructions.size() - 1;
        for (int i = 0; i < instructions.size(); i++) if (instructions.get(i).operation().itemId() == PRR) prrAt = i;
        assertTrue(prrAt >= 0 && prrAt < rootAt, "the Note work still runs before the Note-spending root");
    }

    private static Map<Integer, Long> inputsByItem(ForgeCraftingInstructions.Instruction instruction) {
        return instruction.ingredients().stream().collect(java.util.stream.Collectors.toMap(
                ForgeCraftingInstructions.Input::itemId, ForgeCraftingInstructions.Input::quantity));
    }

    @Test
    void genuineDirectPeerReviewedResearchIngredientStaysAnIngredientOfTheRoot() {
        // The root recipe itself lists 2 Peer Reviewed Research as an ordinary ingredient and spends no Notes.
        var direct = new Recipe(-20, 901, 1, 0, "", List.of(new Ingredient(PRR, 2), new Ingredient(SHARED, 1)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        var p = LegacyForge.planner(List.of(direct, prrA, prrB)).plan(direct, 1, true,
                ForgeProjectPlanner.Inventory.shared(Map.of()), ctx, Map.of((int) NOTE, 0L), Map.of(SHARED, ask(10), B, ask(100)));
        var instructions = ForgeCraftingInstructions.of(p);
        var root = instructions.get(instructions.size() - 1);
        assertTrue(root.root());
        assertEquals(Map.of(PRR, 2L, SHARED, 1L), inputsByItem(root), "a real recipe ingredient is never filtered out by recipe id");
        assertEquals(2, instructions.stream().filter(i -> i.operation().itemId() == PRR).mapToLong(ForgeCraftingInstructions.Instruction::crafts).sum(),
                "its production is its own ordered instruction before the root");
    }

    @Test
    void genuineIngredientAndSyntheticNoteSchedulingAreKeptApartOnTheSameRoot() {
        // The root needs 1 genuine Peer Reviewed Research AND spends Notes through two Note branches (wallet 10 -> scheduling).
        var both = new Recipe(-21, 902, 1, 0, "", List.of(new Ingredient(OUT1, 1), new Ingredient(OUT2, 1), new Ingredient(PRR, 1)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        var p = LegacyForge.planner(List.of(both, branch1, branch2, prrA, prrB)).plan(both, 1, true,
                ForgeProjectPlanner.Inventory.shared(Map.of()), ctx, Map.of((int) NOTE, 10L), Map.of(SHARED, ask(10), B, ask(100)));
        var instructions = ForgeCraftingInstructions.of(p);
        var root = instructions.get(instructions.size() - 1);
        assertTrue(root.root());
        assertEquals(1L, inputsByItem(root).get(PRR), "exactly the one genuine ingredient, not the scheduled Note production");
        long totalPrrCrafts = instructions.stream().filter(i -> i.operation().itemId() == PRR).mapToLong(ForgeCraftingInstructions.Instruction::crafts).sum();
        assertTrue(totalPrrCrafts > 1, "the synthetic Note production is still crafted beyond the one ingredient: " + totalPrrCrafts);
    }
}
