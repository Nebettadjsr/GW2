package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForgeProjectPlannerTest {

    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000, SHARD_ITEM = 20799, MYSTERY = 9999;

    private static Recipe forge(int id, int out, int count, List<Ingredient> in, List<CurrencyRequirement> cur) {
        return new Recipe(-id, out, count, 0, "", in, cur, RecipeSource.MYSTIC_FORGE);
    }

    private static Recipe ordinary(int id, int out, int count, List<Ingredient> in) {
        return new Recipe(id, out, count, 400, "Weaponsmith", in);
    }

    private final Recipe target = forge(1, WEAPON, 1, List.of(new Ingredient(PLATE, 3), new Ingredient(MYSTERY, 2)),
            List.of(new CurrencyRequirement(23, 5)));
    private final Recipe plate = ordinary(10, PLATE, 2, List.of(new Ingredient(ORE, 5)));
    private final ForgeProjectPlanner planner = new ForgeProjectPlanner(List.of(target, plate,
            Recipe.vendor(-1_000_000_001, SHARD_ITEM, 5, List.of(new CurrencyRequirement(23, 3)), 0)));

    private static ForgeProjectPlanner.ItemLine line(ForgeProjectPlanner.Project p, int id) {
        return p.items().stream().filter(i -> i.itemId() == id).findFirst().orElseThrow();
    }

    @Test
    void plansExactlyOneTargetWhateverTheWalletAndNeverMaximises() {
        var p = planner.plan(target, 1, Map.of(), Map.of(), Map.of());
        assertEquals(1, p.batches());
        assertEquals(1, p.guaranteedOutput());
        // 3 plates need ceil(3/2)=2 crafts = 10 ore; the surplus plate is conserved, not rebought.
        assertEquals(2, p.steps().stream().filter(s -> s.recipeId() == 10).findFirst().orElseThrow().crafts());
        assertEquals(10, line(p, ORE).missing());
        assertEquals(2, line(p, MYSTERY).missing());
        assertTrue(p.complete());
    }

    @Test
    void ownedIntermediatesAreUsedBeforeExpandingAndSharedStockIsAggregatedOnce() {
        var p = planner.plan(target, 1, Map.of(PLATE, 2L, ORE, 5L), Map.of(), Map.of());
        assertEquals(2, line(p, PLATE).fromOwned());
        // one plate still needed -> one craft (5 ore) fully covered by owned ore
        assertEquals(5, line(p, ORE).fromOwned());
        assertEquals(0, line(p, ORE).missing());
        assertEquals(1, p.steps().stream().filter(s -> s.recipeId() == 10).findFirst().orElseThrow().crafts());
    }

    @Test
    void currencyIsReportedSeparatelyWithOwnedAndMissingAndNeverPricedInGold() {
        var p = planner.plan(target, 2, Map.of(), Map.of(23, 7L), Map.of());
        var c = p.currencies().get(0);
        assertEquals(23, c.currencyId());
        assertEquals(10, c.needed());
        assertEquals(7L, c.owned());
        assertEquals(3L, c.missing());
        assertEquals(0, p.knownCopper());
    }

    @Test
    void unpricedLeavesAreListedAndOnlyPricedPartsCountTowardKnownGold() {
        var prices = Map.of(ORE, new PriceQuote(1, 4));
        var p = planner.plan(target, 1, Map.of(), Map.of(), prices);
        assertEquals(40, p.knownCopper()); // 10 ore x 4c instant-buy; mystery item unpriced, not free
        assertEquals(List.of(MYSTERY), p.unpricedItemIds());
        assertEquals(4L, line(p, ORE).knownUnitCopper());
        assertNull(line(p, MYSTERY).knownUnitCopper());
    }

    @Test
    void randomOutputRecipeIsNeverAGuaranteedProducer() {
        Recipe random = Recipe.forgeWithExpectedYield(-5, MYSTERY, ExpectedYield.of("3.5", 1, 6), List.of(new Ingredient(ORE, 1)));
        var p = new ForgeProjectPlanner(List.of(target, plate, random)).plan(target, 1, Map.of(), Map.of(), Map.of());
        assertEquals(2, line(p, MYSTERY).missing());
        assertTrue(p.steps().stream().noneMatch(s -> s.recipeId() == -5));
    }

    @Test
    void surplusIsNotPublishedBeforeTheProducingCraftSecuresItsOwnInputs() {
        int bp = 21259, key = 103913;
        Recipe big = forge(2125901, bp, 600, List.of(new Ingredient(bp, 1), new Ingredient(key, 3)), List.of());
        Recipe small = forge(2125902, bp, 200, List.of(new Ingredient(bp, 3), new Ingredient(key, 1)), List.of());
        Recipe top = forge(7, WEAPON, 1, List.of(new Ingredient(bp, 1)), List.of());
        var p = new ForgeProjectPlanner(List.of(top, big, small)).plan(top, 1, Map.of(), Map.of(), Map.of());
        // the blueprint seed needed by the producing craft is external, not financed by that craft's own output
        assertTrue(line(p, bp).missing() > 0, "seed blueprint must still be acquired");
    }

    @Test
    void cyclesTerminateAsLeaves() {
        Recipe a = ordinary(20, PLATE, 1, List.of(new Ingredient(ORE, 1)));
        Recipe b = ordinary(21, ORE, 1, List.of(new Ingredient(PLATE, 1)));
        var p = new ForgeProjectPlanner(List.of(a, b)).plan(target, 1, Map.of(), Map.of(), Map.of());
        assertTrue(p.complete());
        assertTrue(line(p, PLATE).needed() >= 3);
    }

    @Test
    void hugeProjectQuantitiesAreExactWithoutPerUnitSimulation() {
        var p = planner.plan(target, 1_000_000_000L, Map.of(), Map.of(), Map.of());
        assertTrue(p.complete());
        assertEquals(5_000_000_000L, p.currencies().get(0).needed());
        assertEquals(7_500_000_000L, line(p, ORE).missing()); // 3e9 plates = 1.5e9 crafts x 5 ore
    }

    @Test
    void overflowYieldsTruthfulIncompleteResult() {
        var p = planner.plan(target, Long.MAX_VALUE / 2, Map.of(), Map.of(), Map.of());
        assertFalse(p.complete());
        assertNotNull(p.incompleteReason());
    }

    @Test
    void rejectsNonForgeTargetAndNonPositiveBatches() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(plate, 1, Map.of(), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(target, 0, Map.of(), Map.of(), Map.of()));
    }

    @Test
    void vendorBatchRoundsUpAndSpendsCurrencyOnceForTheWholeBatch() {
        Recipe t = forge(2, WEAPON, 1, List.of(new Ingredient(SHARD_ITEM, 7)), List.of());
        var p = new ForgeProjectPlanner(List.of(Recipe.vendor(-1_000_000_001, SHARD_ITEM, 5,
                List.of(new CurrencyRequirement(23, 3)), 0))).plan(t, 1, Map.of(), Map.of(), Map.of());
        assertEquals(2, p.steps().get(0).crafts());
        assertEquals(6, p.currencies().get(0).needed());
    }

    private static ForgeProjectPlanner.Node child(ForgeProjectPlanner.Node n, int itemId) {
        return n.children().stream().filter(c -> c.itemId() == itemId).findFirst().orElseThrow();
    }

    @Test
    void treeSeparatesPlannedCraftingFromOwnedProgressAndPartitionsEveryNode() {
        var p = planner.plan(target, 1, Map.of(ORE, 5L), Map.of(), Map.of());
        var root = p.tree();
        assertEquals(WEAPON, root.itemId());
        var plates = child(root, PLATE);
        // 3 plates needed, none owned: fully planned crafting (2 crafts), NOT progress.
        assertEquals(3, plates.needed());
        assertEquals(0, plates.fromOwned());
        assertEquals(3, plates.toCraft());
        assertEquals(0, plates.toAcquire());
        assertEquals(2, plates.crafts());
        var ore = child(plates, ORE);
        assertEquals(10, ore.needed());
        assertEquals(5, ore.fromOwned());
        assertEquals(5, ore.toAcquire());
        var mystery = child(root, MYSTERY);
        assertEquals(2, mystery.toAcquire());
        assertEquals(0, mystery.fromOwned());
        assertPartition(root);
    }

    private static void assertPartition(ForgeProjectPlanner.Node n) {
        if (n.recipeId() == null || n.children().isEmpty()) {
            assertEquals(n.needed(), n.fromOwned() + n.fromSurplus() + n.toCraft() + n.toAcquire(), "node " + n.itemId());
        }
        n.children().forEach(ForgeProjectPlannerTest::assertPartition);
    }

    @Test
    void surplusFromOneBranchServesALaterBranchOnceAndIsReportedAsSurplusNotOwned() {
        // Two sibling demands for plates: 3 plates (2 crafts -> 4 produced) then 1 more -> served from the 1 surplus.
        Recipe twice = forge(2, WEAPON + 1, 1, List.of(new Ingredient(PLATE, 3), new Ingredient(PLATE, 1)), List.of());
        var pl = new ForgeProjectPlanner(List.of(twice, plate));
        var p = pl.plan(twice, 1, Map.of(), Map.of(), Map.of());
        assertEquals(2, p.steps().stream().filter(s -> s.recipeId() == 10).findFirst().orElseThrow().crafts());
        var second = p.tree().children().get(1);
        assertEquals(1, second.fromSurplus());
        assertEquals(0, second.fromOwned());
        assertEquals(0, second.toCraft());
    }

    @Test
    void unknownWalletStaysUnknownWhileAnEmptyKnownWalletIsZero() {
        var unknown = planner.plan(target, 1, Map.of(), null, Map.of());
        assertNull(unknown.currencies().get(0).owned());
        assertNull(unknown.currencies().get(0).missing());
        var empty = planner.plan(target, 1, Map.of(), Map.of(23, 0L), Map.of());
        assertEquals(0L, empty.currencies().get(0).owned());
        assertEquals(5L, empty.currencies().get(0).missing());
    }

    @Test
    void cloverReferenceRecipesAreNeverProducersAndNestedCloverDemandStaysAnAcquireLeaf() {
        var refs = ForgeReferenceRecipes.all();
        assertEquals(2, refs.size());
        assertEquals(List.of(19925, 19976, 19721, 20796), refs.get(0).ingredients().stream().map(i -> i.itemId).toList());
        assertEquals(6, refs.get(0).ingredients().get(3).count);
        assertEquals(20799, refs.get(1).ingredients().get(3).itemId);
        assertEquals(10, refs.get(1).ingredients().get(3).count);
        // A Forge recipe needing 77 Clovers: even if the planner is (wrongly) offered a reference-derived recipe it only
        // becomes a producer when passed explicitly; the production producer list never includes references.
        Recipe needsClover = forge(3, WEAPON + 2, 1, List.of(new Ingredient(19675, 77)), List.of());
        var pl = new ForgeProjectPlanner(List.of(needsClover, plate));
        var p = pl.plan(needsClover, 1, Map.of(), Map.of(), Map.of());
        assertEquals(77, line(p, 19675).missing());
        assertTrue(p.steps().stream().noneMatch(s -> s.outputItemId() == 19675));
        // A reference target plans only its inputs and claims no output units from the random outcome.
        var refPlan = pl.plan(refs.get(0).asPlanningTarget(), 1, Map.of(19925, 1L), Map.of(), Map.of());
        assertEquals(1, line(refPlan, 19925).fromOwned());
        assertEquals(6, line(refPlan, 20796).missing());
    }
}
