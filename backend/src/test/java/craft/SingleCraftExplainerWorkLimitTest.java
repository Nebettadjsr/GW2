package craft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The selected-result explanation honours the same per-root WORK_LIMIT policy as the table: an accepted prefix is
 * explained exactly (never more crafts than the table counted) and a zero-accepted limited attempt stays blocked and
 * finite. Branching graph: every level offers an ordinary recipe and a Mystic Forge recipe, each consuming one unit of
 * the next level, so one accepted execution consumes one leaf while the alternatives multiply the speculative work.
 */
class SingleCraftExplainerWorkLimitTest {
    private static final int LEVELS = 4;
    private static final int TOP = 930_000;
    private static final int LEAF = TOP + LEVELS;
    private static final int LEAF_STOCK = 40;

    private final List<Recipe> recipes = new ArrayList<>();
    private final Set<Integer> allowed = new HashSet<>();
    private final List<CharacterCraftingProfile> roster = List.of(
            new CharacterCraftingProfile("A", Map.of("Chef", 500)));
    private final Map<Integer, PriceQuote> tp = Map.of(TOP, new PriceQuote(900, 1000), LEAF, new PriceQuote(5, 10));
    private final CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);

    private Recipe top;

    SingleCraftExplainerWorkLimitTest() {
        for (int i = 0; i < LEVELS; i++) {
            int out = TOP + i, in = TOP + i + 1;
            recipes.add(new Recipe(10 + i, out, 1, 0, "Chef", List.of(new Ingredient(in, 1))));
            recipes.add(Recipe.forgeWithExpectedYield(-10 - i, out, ExpectedYield.of("2.5", 2, 3), List.of(new Ingredient(in, 1))));
        }
        recipes.forEach(r -> allowed.add(r.recipeId));
        top = recipes.getFirst();
    }

    private CraftResult table(int limit) {
        return new CraftingPlanner().withWorkLimit(limit).evaluateAllCoordinated(recipes, Map.of(LEAF, LEAF_STOCK),
                Map.of(), Map.of(), roster, tp, settings, Set.of(top.recipeId)).get(top.recipeId);
    }

    private SingleCraftExplanation explain(int limit, int counted) {
        return new SingleCraftExplainer().withWorkLimit(limit).explainCoordinatedResult(top, recipes,
                Map.of(LEAF, LEAF_STOCK), Map.of(), Map.of(), roster, tp, settings, allowed, counted);
    }

    private static int nodes(CraftTraceNode n) {
        return 1 + n.children().stream().mapToInt(SingleCraftExplainerWorkLimitTest::nodes).sum();
    }

    @Test
    void acceptedPrefixIsExplainedExactlyAndNeverExtendedByTheWorkLimit() {
        int limit = 20;
        CraftResult t = table(limit);
        assertEquals(BlockedReason.WORK_LIMIT, t.blockedReason);
        assertEquals(10, t.craftableCount, "the limited table accepts a strict, positive prefix");

        SingleCraftExplanation ex = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> explain(limit, t.craftableCount));
        assertTrue(ex.available());
        CraftTraceNode root = ex.root();
        assertEquals(TOP, root.itemId());
        assertEquals(t.craftableCount, root.craftCount(), "exactly the counted crafts, no invented additional crafts");
        assertEquals(t.craftableCount, root.producedQuantity());
        assertEquals(t.craftableCount, root.requestedQuantity());
        assertEquals(t.craftableCount, ex.outputQuantity());
        assertEquals(0, root.missingQuantity());
        assertFalse(root.states().contains(ResolutionState.BLOCKED));
        assertEquals(List.of(), root.blockedReasons());
        // Per-craft cost partition: every accepted execution consumed exactly one unit of the next level.
        assertEquals(t.craftableCount * (long) t.matsSellValueCopper, root.opportunityCostCopper().longValue());
    }

    @Test
    void zeroAcceptedLimitedAttemptStaysBlockedWithTheWorkLimitReasonAndAFiniteTree() {
        int limit = 1;
        CraftResult t = table(limit);
        assertEquals(BlockedReason.WORK_LIMIT, t.blockedReason);
        assertEquals(0, t.craftableCount);

        SingleCraftExplanation ex = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> explain(limit, 0));
        assertTrue(ex.available(), "the rejected attempt is still an explanation");
        CraftTraceNode root = ex.root();
        assertTrue(root.states().contains(ResolutionState.BLOCKED));
        assertTrue(root.blockedReasons().contains(BlockedReason.WORK_LIMIT), "reasons: " + root.blockedReasons());
        assertEquals(0, root.craftCount());
        assertEquals(0, root.producedQuantity());
        assertTrue(nodes(root) <= 2 * LEVELS + 2, "finite tree under the limit, got " + nodes(root));

        // Control: without the table's limit the same attempt would have been accepted - the limit is what blocks it.
        SingleCraftExplanation unlimited = explain(PlanState.MAX_WORK_UNITS, 1);
        assertEquals(1, unlimited.root().craftCount());
        assertFalse(unlimited.root().states().contains(ResolutionState.BLOCKED));
    }
}
