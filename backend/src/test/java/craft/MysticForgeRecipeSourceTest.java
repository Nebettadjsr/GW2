package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Forge recipes are setting-gated, need no discipline/unlock/crafter, and never relax ordinary eligibility. */
class MysticForgeRecipeSourceTest {

    private static final int ROOT = 900_100, FORGED = 900_102, STONE = 900_101, HARD = 900_103, HARD_ROOT = 900_104;
    private static final int FORGE_ID = 3_105_801;

    private final Recipe forge = new Recipe(-FORGE_ID, FORGED, 1, 0, "", List.of(new Ingredient(STONE, 2)),
            List.of(), RecipeSource.MYSTIC_FORGE);
    // Ordinary recipes: the root consumes the forged item; HARD_ROOT needs rating 400 nobody has.
    private final Recipe root = new Recipe(1, ROOT, 1, 0, "Armorsmith", List.of(new Ingredient(FORGED, 1)));
    private final Recipe hardIntermediate = new Recipe(2, HARD, 1, 400, "Armorsmith", List.of(new Ingredient(STONE, 1)));
    private final Recipe hardRoot = new Recipe(3, HARD_ROOT, 1, 0, "Armorsmith",
            List.of(new Ingredient(HARD, 1), new Ingredient(FORGED, 1)));
    private final List<Recipe> all = List.of(forge, root, hardIntermediate, hardRoot);
    private final Set<Integer> allowed = Set.of(1, 2, 3, -FORGE_ID);
    private final List<CharacterCraftingProfile> roster =
            List.of(new CharacterCraftingProfile("Smith", Map.of("Armorsmith", 100)));
    private final Map<Integer, PriceQuote> tp = Map.of(
            STONE, new PriceQuote(null, 10),       // instant buy 10 copper
            ROOT, new PriceQuote(1000, null),      // instant sell 1000 -> 850 after the 15% fee
            FORGED, new PriceQuote(100, null),
            HARD_ROOT, new PriceQuote(1000, null));

    private Map<Integer, CraftResult> run(boolean forgeAllowed) {
        CraftingSettings s = new CraftingSettings(false, true, 0, false, false, false).withMysticForge(forgeAllowed);
        return new CraftingPlanner().evaluateAllCoordinated(all, Map.of(), Map.of(), Map.of(), roster, tp, s, allowed);
    }

    @Test
    void toggleOffKeepsForgeOutOfTheOrdinaryResult() {
        Map<Integer, CraftResult> off = run(false);
        assertEquals(0, off.get(1).craftableCount, "no way to obtain the forged intermediate while disabled");
        assertEquals(0, off.get(-FORGE_ID).craftableCount, "a Forge root is blocked while disabled");
    }

    @Test
    void enabledForgeFeedsNestedOrdinaryRecipeWithoutAnyCrafter() {
        CraftResult r = run(true).get(1);
        assertEquals(250, r.craftableCount);
        // Independently: 1000 * 0.85 - (2 stones * 10) = 830 per craft.
        assertEquals(830, r.profitCopper);
        assertEquals(830 * 250, r.totalProfitCopper);
    }

    @Test
    void forgeRootIsCalculatedWithoutDisciplineRatingOrUnlock() {
        // The roster has no Forge discipline and the recipe id is not an account unlock.
        CraftResult r = run(true).get(-FORGE_ID);
        assertEquals(250, r.craftableCount);
        // 100 * 0.85 - 20 = 65
        assertEquals(65, r.profitCopper);
    }

    @Test
    void ordinaryIntermediateStillRequiresRating() {
        assertEquals(0, run(true).get(3).craftableCount, "rating-400 intermediate stays blocked despite Forge");
    }

    @Test
    void withMysticForgeKeepsEveryOtherSetting() {
        CraftingSettings s = new CraftingSettings(true, true, 55, true, false, true, true, Set.of(7))
                .withMysticForge(true);
        assertTrue(s.allowMysticForge);
        assertTrue(s.useOwnMats && s.allowBuying && s.listingSell && s.allowDailyCrafts);
        assertEquals(55, s.maxBuyCopper);
        assertTrue(s.isProtected(7));
        assertTrue(s.withBuyingDisabled().allowMysticForge);
        assertFalse(s.withMysticForge(false).allowMysticForge);
    }
}
