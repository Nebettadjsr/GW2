package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** STORY-FIX-005: crafts count executions of the selected recipe, never finished stock. */
class RootRecipeExecutionTest {

    private static final int OUT = 910_001;
    private static final int A = 910_002;
    private static final int B = 910_003;
    private static final int MID = 910_004;
    private static final int TOP = 910_005;

    private static CraftResult run(Recipe selected, List<Recipe> all, Map<Integer, Integer> inv,
                                   Map<Integer, PriceQuote> prices, CraftingSettings settings) {
        return new CraftingPlanner().evaluateOne(selected, all, inv, Map.of(), prices, settings,
                Set.of(1, 2, 3, 4, 5));
    }

    private static CraftingSettings settings(boolean own, boolean buy) {
        return new CraftingSettings(own, buy, 0, false, false, false);
    }

    @Test
    void finishedStockWithoutIngredientsProducesZeroCrafts() {
        Recipe r = CraftTestFixtures.recipe(1, OUT, List.of(CraftTestFixtures.ingredient(A, 1)));
        CraftResult res = run(r, List.of(r), Map.of(OUT, 129), Map.of(), settings(true, false));
        assertEquals(0, res.craftableCount);
    }

    @Test
    void buyingTheFinishedOutputCannotSatisfyTheRootExecution() {
        Recipe r = CraftTestFixtures.recipe(1, OUT, List.of(CraftTestFixtures.ingredient(A, 1)));
        CraftResult res = run(r, List.of(r), Map.of(),
                Map.of(OUT, CraftTestFixtures.quote(1, 1)), settings(true, true));
        assertEquals(0, res.craftableCount);
    }

    @Test
    void recipesSharingAnOutputKeepTheirOwnIngredientAvailability() {
        Recipe cheap = CraftTestFixtures.recipe(1, OUT, List.of(CraftTestFixtures.ingredient(A, 1)));
        Recipe other = CraftTestFixtures.recipe(2, OUT, List.of(CraftTestFixtures.ingredient(B, 1)));
        List<Recipe> all = List.of(cheap, other);
        Map<Integer, Integer> inv = Map.of(A, 3);
        assertEquals(3, run(cheap, all, inv, Map.of(), settings(true, false)).craftableCount);
        assertEquals(0, run(other, all, inv, Map.of(), settings(true, false)).craftableCount);
    }

    @Test
    void rootOutputCountIsUsedAndOwnedIntermediatesRemainUsable() {
        Recipe top = CraftTestFixtures.recipe(1, TOP, 3, List.of(CraftTestFixtures.ingredient(MID, 2)));
        Recipe mid = CraftTestFixtures.recipe(2, MID, List.of(CraftTestFixtures.ingredient(A, 1)));
        CraftResult res = run(top, List.of(top, mid), Map.of(MID, 4, A, 2), Map.of(), settings(true, false));
        assertEquals(3, res.craftableCount); // 4 owned MID = 2 crafts; 2 A craft 2 more MID = 1 craft
        assertEquals(TOP, res.outputItemId);
    }

    @Test
    void maximumOf250IsNotExceededWhenBuyingFollowsTheOwnOnlyPhase() {
        Recipe r = CraftTestFixtures.recipe(1, OUT, List.of(CraftTestFixtures.ingredient(A, 1)));
        Map<Integer, PriceQuote> prices = Map.of(A, CraftTestFixtures.quote(1, 1));
        assertEquals(250, run(r, List.of(r), Map.of(A, 250), prices, settings(true, true)).craftableCount);
        assertEquals(250, run(r, List.of(r), Map.of(A, 1000), prices, settings(true, true)).craftableCount);
        assertEquals(250, run(r, List.of(r), Map.of(A, 10), prices, settings(true, true)).craftableCount);
    }
}
