package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Independent expected quantities for binding pools, self-seed reuse, unknown-vs-zero facts and promotion economics. */
class ForgeProjectBindingSeedTest {

    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000;

    private static Recipe forge(int id, int out, int count, List<Ingredient> in) {
        return new Recipe(-id, out, count, 0, "", in, List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static ForgeProjectPlanner.Node child(ForgeProjectPlanner.Node n, int itemId) {
        return n.children().stream().filter(c -> c.itemId() == itemId).findFirst().orElseThrow();
    }

    private final Recipe weapon = forge(1, WEAPON, 1, List.of(new Ingredient(PLATE, 2)));
    // ordinary step: needs Weaponsmith 400 and ORE x5 per 2 plates
    private final Recipe plate = new Recipe(10, PLATE, 2, 400, "Weaponsmith", List.of(new Ingredient(ORE, 5)));
    private final ForgeProjectPlanner planner = new ForgeProjectPlanner(List.of(weapon, plate));
    private final List<CharacterCraftingProfile> roster = List.of(
            new CharacterCraftingProfile("Smith", Map.of("Weaponsmith", 500)),
            new CharacterCraftingProfile("Novice", Map.of("Weaponsmith", 100)),
            new CharacterCraftingProfile("Other", Map.of("Weaponsmith", 500)));

    @Test
    void ordinaryStepUsesOnlyItsAssignedEligibleCharactersCarriedAndBankStockNeverPoolsOwners() {
        // Smith carries 3 ore, Other carries 4 ore, Novice (ineligible) carries 100. Bank-bound ore of Other: 1.
        var inv = new ForgeProjectPlanner.Inventory(Map.of(), Map.of(), Map.of(
                "Smith", Map.of(ORE, 3), "Other", Map.of(ORE, 4), "Novice", Map.of(ORE, 100),
                PlanState.bankOwnerKey("Other"), Map.of(ORE, 1)));
        var p = planner.planOutput(weapon, 1, inv, new ForgeProjectPlanner.Context(roster, Set.of(10)), Map.of(), Map.of());
        var node = child(p.tree(), PLATE);
        assertEquals("Other", node.assignedCharacter(), "Other holds 4+1 relevant ore vs Smith 3: deterministic best holder");
        assertTrue(node.performable());
        assertEquals(Boolean.TRUE, node.recipeKnown());
        var ore = child(node, ORE);
        assertEquals(5, ore.needed());
        assertEquals(5, ore.fromOwned(), "own carried 4 + own Bank-bound 1; Smith/Novice stock is never pooled in");
        assertEquals(0, ore.toAcquire());
    }

    @Test
    void forgeStepMayUseAnyOwnersBankButNotOtherCharactersCarried() {
        Recipe direct = forge(2, WEAPON, 1, List.of(new Ingredient(ORE, 10)));
        var pl = new ForgeProjectPlanner(List.of(direct));
        var inv = new ForgeProjectPlanner.Inventory(Map.of(), Map.of(), Map.of(
                PlanState.bankOwnerKey("A"), Map.of(ORE, 3), PlanState.bankOwnerKey("B"), Map.of(ORE, 2),
                "A", Map.of(ORE, 4), "B", Map.of(ORE, 4)));
        var p = pl.planOutput(direct, 1, inv, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        // 3+2 Bank (any owner) + ONE character's carried 4 = 9; the other carried 4 is not pooled -> 1 missing
        assertEquals(9, p.items().stream().filter(i -> i.itemId() == ORE).findFirst().orElseThrow().fromOwned());
        assertEquals(1, p.items().stream().filter(i -> i.itemId() == ORE).findFirst().orElseThrow().missing());
    }

    @Test
    void lockedOrdinaryStepStillShowsInputsButIsNotPerformable() {
        var inv = new ForgeProjectPlanner.Inventory(Map.of(ORE, 5), Map.of(), Map.of());
        var p = planner.planOutput(weapon, 1, inv, new ForgeProjectPlanner.Context(roster, Set.of()), Map.of(), Map.of());
        var node = child(p.tree(), PLATE);
        assertEquals(Boolean.FALSE, node.recipeKnown());
        assertFalse(node.performable());
        assertEquals(5, child(node, ORE).needed(), "inputs of a locked step stay visible");
    }

    @Test
    void noEligibleCharacterMeansNotPerformableAndSharedStockOnly() {
        var inv = new ForgeProjectPlanner.Inventory(Map.of(ORE, 2), Map.of(), Map.of("Novice", Map.of(ORE, 50)));
        var p = planner.planOutput(weapon, 1, inv,
                new ForgeProjectPlanner.Context(List.of(roster.get(1)), Set.of(10)), Map.of(), Map.of());
        var node = child(p.tree(), PLATE);
        assertNull(node.assignedCharacter());
        assertFalse(node.performable());
        assertEquals(2, child(node, ORE).fromOwned());
        assertEquals(3, child(node, ORE).toAcquire());
    }

    // ---- self-seed ----

    private static Recipe gossamer() {
        // output 19745 min 10 mean 18.92; seed 19745 x1; plus 19748 x250, 24277 x5 (Stones), 20796 x5 (Dust)
        return Recipe.forgeWithExpectedYield(-77, 19745, ExpectedYield.of("18.92", 10, 40), List.of(
                new Ingredient(19748, 250), new Ingredient(19745, 1), new Ingredient(24277, 5), new Ingredient(20796, 5)));
    }

    @Test
    void gossamerTwoBatchesOneExternalSeedGuaranteedReuseAndOracleEconomics() {
        Recipe g = gossamer();
        var pl = new ForgeProjectPlanner(List.of(g, Recipe.vendor(-1_000_000_001, 20796, 10, List.of(new CurrencyRequirement(23, 1)), 0)));
        Map<Integer, PriceQuote> tp = Map.of(19745, new PriceQuote(90, 100), 19748, new PriceQuote(1, 2),
                20796, new PriceQuote(19, 20), 24277, new PriceQuote(1, 3));
        var inv = ForgeProjectPlanner.Inventory.shared(Map.of(24277, 10L)); // Stones sufficiently owned
        var p = pl.plan(g, 2, true, inv, ForgeProjectPlanner.Context.NONE, Map.of(23, 100L), tp);
        var s = p.seed();
        assertEquals(1, s.externalSeedUnits());
        assertEquals(1, s.reusedSeedUnits());
        assertEquals(20, s.grossGuaranteedOutput());
        assertEquals(19, s.netGuaranteedOutput());
        assertEquals(0, new java.math.BigDecimal("36.84").compareTo(s.netExpectedOutput()));
        assertEquals(0, new java.math.BigDecimal("37.84").compareTo(s.grossExpectedOutput()));
        // inputs: 19748 x500 (not x2 seed), seed x1 external only
        var seedLine = p.items().stream().filter(i -> i.itemId() == 19745).findFirst().orElseThrow();
        assertEquals(1, seedLine.needed(), "the seed is not multiplied by the batch count nor produced recursively");
        assertEquals(1, seedLine.missing());
        var e = p.economics();
        assertEquals(100, e.outputUnitSellCopper());
        assertEquals(3684, e.expectedGrossProceedsCopper());
        assertEquals(553, e.feeCopper());
        assertEquals(3131, e.expectedNetProceedsCopper());
        // 500x2c + 10 dust: purchased via vendor batch (1 shard) is currency, priced items are leaves only
        // 24277 owned. Dust comes from the vendor producer (1 Spirit Shard per 10), not a gold purchase.
        assertTrue(e.conditional(), "Spirit Shards stay unpriced: the margin is conditional");
        assertEquals(1000 + 100, p.knownCopper(), "500 lower-bound inputs x2c + one 100c seed; dust is a shard purchase");
        assertEquals(3131 - 1100, e.marginOnShoppingCopper());
        // replacement cost values the OWNED stones too (10 owned used 10: 5 per batch x2) at 3c
        assertEquals(1100 + 10 * 3, e.knownReplacementCopper());
    }

    @Test
    void viciousClawFiftyBatchesReusesOnlyGuaranteedMinimum() {
        Recipe c = Recipe.forgeWithExpectedYield(-78, 24350, ExpectedYield.of("6.91", 5, 12),
                List.of(new Ingredient(24350, 1), new Ingredient(24341, 50)));
        var p = new ForgeProjectPlanner(List.of(c)).plan(c, 50, Map.of(), Map.of(), Map.of());
        assertEquals(1, p.seed().externalSeedUnits());
        assertEquals(49, p.seed().reusedSeedUnits());
        assertEquals(250, p.seed().grossGuaranteedOutput());
        assertEquals(201, p.seed().netGuaranteedOutput());
        assertEquals(0, new java.math.BigDecimal("296.50").compareTo(p.seed().netExpectedOutput()));
        assertEquals(2500, p.items().stream().filter(i -> i.itemId() == 24341).findFirst().orElseThrow().needed());
    }

    @Test
    void seedLargerThanGuaranteedMinimumNeedsTopUpEachBatch() {
        Recipe r = Recipe.forgeWithExpectedYield(-79, 500, ExpectedYield.of("3", 2, 4),
                List.of(new Ingredient(500, 3), new Ingredient(501, 1)));
        var p = new ForgeProjectPlanner(List.of(r)).plan(r, 4, Map.of(), Map.of(), Map.of());
        assertEquals(3 + 3 * 1, p.seed().externalSeedUnits(), "each later batch can reuse only min=2 of its 3 seed units");
        assertEquals(6, p.seed().reusedSeedUnits());
    }

    // ---- unknown vs zero ----

    @Test
    void knownWalletMissingCurrencyIsZeroButNullWalletIsUnknown() {
        Recipe r = new Recipe(-5, WEAPON, 1, 0, "", List.of(), List.of(new CurrencyRequirement(23, 5)), RecipeSource.MYSTIC_FORGE);
        var pl = new ForgeProjectPlanner(List.of(r));
        var known = pl.plan(r, 1, Map.of(), Map.of(61, 9L), Map.of()).currencies().getFirst();
        assertEquals(0L, known.owned());
        assertEquals(5L, known.missing());
        var unknown = pl.plan(r, 1, Map.of(), null, Map.of()).currencies().getFirst();
        assertNull(unknown.owned());
        assertNull(unknown.missing());
    }

    @Test
    void zeroOrNegativeSellQuoteIsUnpricedNotFree() {
        var p = planner.plan(weapon, 1, Map.of(), Map.of(), Map.of(ORE, new PriceQuote(0, 0), PLATE, new PriceQuote(1, -3)));
        var ore = p.items().stream().filter(i -> i.itemId() == ORE).findFirst().orElseThrow();
        assertNull(ore.knownUnitCopper());
        assertTrue(p.unpricedItemIds().contains(ORE));
        assertEquals(0, p.knownCopper());
        assertEquals(5, ore.missing(), "siblings and required counts survive an unpriced branch");
    }

    @Test
    void fixedRootConsumesAlreadyOwnedOutputBeforeExpanding() {
        var inv = ForgeProjectPlanner.Inventory.shared(Map.of(WEAPON, 2L));
        var p = planner.planOutput(weapon, 3, inv, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertEquals(1, p.batches(), "3 wanted - 2 owned = 1 craft");
        assertEquals(2, p.tree().fromOwned());
        assertEquals(1, p.tree().toCraft());
        var none = planner.planOutput(weapon, 2, inv, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertEquals(0, none.batches());
        assertTrue(none.steps().isEmpty());
        assertTrue(none.complete());
    }

    @Test
    void runestonePurchaseIsTenThousandCopperPerUnitWithMasteryHint() {
        Recipe r = forge(3, WEAPON, 1, List.of(new Ingredient(VendorAcquisitions.MYSTIC_RUNESTONE, 100)));
        var pl = new ForgeProjectPlanner(List.of(r, VendorAcquisitions.recipes().stream()
                .filter(x -> x.outputItemId == VendorAcquisitions.MYSTIC_RUNESTONE).findFirst().orElseThrow()));
        var p = pl.planOutput(r, 1, ForgeProjectPlanner.Inventory.shared(Map.of()), ForgeProjectPlanner.Context.NONE,
                Map.of(), Map.of(VendorAcquisitions.MYSTIC_RUNESTONE, new PriceQuote(1, 330)));
        assertEquals(1_000_000, p.knownCopper(), "100 x 100s, not the 330c vendor sell value");
        assertNotNull(p.tree().children().getFirst().hint());
        assertTrue(p.tree().children().getFirst().hint().contains("Scholar of Secrets"));
    }
}
