package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Binding-aware, hierarchical equal-share readiness through the real planner entry point; literal expectations. */
class ForgeMaterialProgressTest {
    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000, DUST = 3001, OTHER = 3002, VENDOR_A = 4000, VENDOR_B = 4001,
            RANDOM = 5000, COINS = 1;
    private static final ForgeProjectPlanner.Context NONE = ForgeProjectPlanner.Context.NONE;

    private static Recipe forge(int id, int out, List<Ingredient> in) {
        return new Recipe(-id, out, 1, 0, "", in, List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static ForgeProjectPlanner.Inventory inv(Map<Integer, Integer> sellable, Map<String, Map<Integer, Integer>> bound) {
        return new ForgeProjectPlanner.Inventory(sellable, Map.of(), bound);
    }

    private static ForgeMaterialProgress.Progress progress(Recipe root, List<Recipe> all, ForgeProjectPlanner.Inventory inv,
                                                           ForgeProjectPlanner.Context ctx, Map<Integer, Long> wallet) {
        return LegacyForge.planner(all).materialProgress(root, 1, inv, ctx, wallet);
    }

    @Test
    void carriedSoulboundStockOfTwoCharactersIsNotPooledForOneForgeOperation() {
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(ORE, 2)));
        var p = progress(root, List.of(root), inv(Map.of(), Map.of("A", Map.of(ORE, 1), "B", Map.of(ORE, 1))), NONE, Map.of());
        assertEquals(50.0, p.percent(), "one carrier supplies 1 of 2; the other copy is on a different character");
    }

    @Test
    void bankBoundStockOfDifferentOwnersIsAcceptedByForgeAndAddsToOneCarrier() {
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(ORE, 3)));
        var bank = inv(Map.of(), Map.of(PlanState.bankOwnerKey("A"), Map.of(ORE, 1), PlanState.bankOwnerKey("B"), Map.of(ORE, 1),
                "C", Map.of(ORE, 1)));
        assertEquals(100.0, progress(root, List.of(root), bank, NONE, Map.of()).percent());
    }

    @Test
    void nestedOrdinaryCraftUsesOnlyItsAssignedEligibleCharactersStock() {
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(PLATE, 2)));
        Recipe plate = new Recipe(10, PLATE, 2, 400, "Weaponsmith", List.of(new Ingredient(ORE, 5)));
        var ctx = new ForgeProjectPlanner.Context(List.of(
                new CharacterCraftingProfile("Smith", Map.of("Weaponsmith", 500)),
                new CharacterCraftingProfile("Novice", Map.of("Weaponsmith", 100))), Set.of(10));
        var split = inv(Map.of(), Map.of("Smith", Map.of(ORE, 3), "Novice", Map.of(ORE, 50)));
        assertEquals(60.0, progress(root, List.of(root, plate), split, ctx, Map.of()).percent());
        var ctx2 = new ForgeProjectPlanner.Context(List.of(
                new CharacterCraftingProfile("Smith", Map.of("Weaponsmith", 500)),
                new CharacterCraftingProfile("Other", Map.of("Weaponsmith", 500))), Set.of(10));
        var two = inv(Map.of(), Map.of("Smith", Map.of(ORE, 3), "Other", Map.of(ORE, 3)));
        assertEquals(60.0, progress(root, List.of(root, plate), two, ctx2, Map.of()).percent());
        var enough = inv(Map.of(), Map.of("Smith", Map.of(ORE, 5)));
        assertEquals(100.0, progress(root, List.of(root, plate), enough, ctx, Map.of()).percent());
    }

    @Test
    void ownedParentShortCircuitsChildren() {
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(PLATE, 2)));
        Recipe plate = new Recipe(10, PLATE, 2, 0, "", List.of(new Ingredient(ORE, 5)));
        assertEquals(100.0, progress(root, List.of(root, plate), inv(Map.of(PLATE, 2), Map.of()), NONE, Map.of()).percent());
    }

    @Test
    void partialSubrecipeCountsEqualSharesAtEachLevel() {
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(PLATE, 2), new Ingredient(OTHER, 1)));
        Recipe plate = new Recipe(10, PLATE, 2, 0, "", List.of(new Ingredient(ORE, 5), new Ingredient(DUST, 5)));
        var p = progress(root, List.of(root, plate), inv(Map.of(ORE, 5, OTHER, 1), Map.of()), NONE, Map.of());
        assertEquals(75.0, p.percent(), "plate = (1 + 0) / 2 = 0.5; root = (0.5 + 1) / 2");
    }

    @Test
    void sufficientWalletForVendorPurchaseContributesReadiness() {
        Recipe vendor = Recipe.vendor(-50, VENDOR_A, 1, List.of(), 100);
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(VENDOR_A, 1)));
        assertEquals(100.0, progress(root, List.of(root, vendor), inv(Map.of(), Map.of()), NONE, Map.of(COINS, 100L)).percent());
        assertEquals(50.0, progress(root, List.of(root, vendor), inv(Map.of(), Map.of()), NONE, Map.of(COINS, 50L)).percent());
    }

    @Test
    void sharedWalletIsDebitedOnceAcrossBranches() {
        Recipe a = Recipe.vendor(-50, VENDOR_A, 1, List.of(), 100);
        Recipe b = Recipe.vendor(-51, VENDOR_B, 1, List.of(), 100);
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(VENDOR_A, 1), new Ingredient(VENDOR_B, 1)));
        var p = progress(root, List.of(root, a, b), inv(Map.of(), Map.of()), NONE, Map.of(COINS, 100L));
        assertEquals(50.0, p.percent(), "100 copper pays one of the two 100-copper purchases, not both");
    }

    @Test
    void sharedItemIsDebitedOnceAcrossBranches() {
        Recipe helper = new Recipe(10, OTHER, 1, 0, "", List.of(new Ingredient(ORE, 1)));
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(ORE, 1), new Ingredient(OTHER, 1)));
        var p = progress(root, List.of(root, helper), inv(Map.of(ORE, 1), Map.of()), NONE, Map.of());
        assertEquals(50.0, p.percent(), "the single ore is spent on the direct input; the helper craft finds none");
    }

    @Test
    void unknownWalletRandomOutputAndCutoffStayTruthful() {
        Recipe vendor = Recipe.vendor(-50, VENDOR_A, 1, List.of(), 100);
        Recipe root = forge(1, WEAPON, List.of(new Ingredient(VENDOR_A, 1)));
        var unknown = progress(root, List.of(root, vendor), inv(Map.of(), Map.of()), NONE, null);
        assertTrue(unknown.percent() < 100 && unknown.partial(), "unknown wallet is never treated as sufficient");
        Recipe random = Recipe.forgeWithExpectedYield(-77, RANDOM, ExpectedYield.of("18.92", 10, 40), List.of(new Ingredient(ORE, 1)));
        Recipe viaRandom = forge(2, WEAPON, List.of(new Ingredient(RANDOM, 1), new Ingredient(OTHER, 1)));
        var r = progress(viaRandom, List.of(viaRandom, random), inv(Map.of(OTHER, 1, ORE, 1), Map.of()), NONE, Map.of());
        assertEquals(50.0, r.percent(), "a random-output producer is never claimed ready");
        Recipe loopA = new Recipe(11, PLATE, 1, 0, "", List.of(new Ingredient(OTHER, 1)));
        Recipe loopB = new Recipe(12, OTHER, 1, 0, "", List.of(new Ingredient(PLATE, 1)));
        Recipe cyc = forge(3, WEAPON, List.of(new Ingredient(PLATE, 1)));
        var c = progress(cyc, List.of(cyc, loopA, loopB), inv(Map.of(), Map.of()), NONE, Map.of());
        assertTrue(c.percent() < 100 && c.partial(), "cycle cutoff is partial, never complete");
    }
}
