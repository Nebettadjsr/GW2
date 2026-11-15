package craft;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #223: a terminal safety failure ends the ancestor attempt, including after ordinary failures. */
class CraftingTerminalFailurePropagationTest {

    @ParameterizedTest
    @EnumSource(value = BlockedReason.class, names = {"MATERIAL_LIMIT", "VALUE_RANGE"})
    void aTerminalFailureAfterAnOrdinaryFailureStopsLaterRootSiblings(BlockedReason terminalReason) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(300, 1)));
        Recipe firstBranch = recipe(2, 200, List.of(ingredient(400, 1), ingredient(500, 1)));
        // Item 400 is unavailable with buying disabled. A later requirement in the same branch
        // exceeds either the material bound or the representable owned-material value.
        int quantity = terminalReason == BlockedReason.MATERIAL_LIMIT ? 2_501 : 2;
        Recipe terminalBranch = recipe(3, 500, List.of(ingredient(600, quantity)));
        List<Recipe> recipes = List.of(root, firstBranch, terminalBranch);
        CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, false);
        Map<Integer, PriceQuote> prices = Map.of(300, quote(5, 5),
                600, terminalReason == BlockedReason.VALUE_RANGE
                        ? quote(1_500_000_000, 1_500_000_000) : quote(1, 1));
        RecordingState state = new RecordingState(Map.of(300, 1, 600, quantity));
        PlannerContext context = new PlannerContext(CraftingPlanner.buildRecipesByOutput(recipes),
                prices, settings, Set.of(1, 2, 3));

        ResolvedNeed result = new CraftingResolver(true).resolveOneCraft(root, context, state).getRoot();
        ResolvedNeed rootAttempt = result.getTrace().getCraftAttempt();
        ResolvedNeed firstNeed = rootAttempt.getChildren().getFirst();
        List<ResolvedNeed> firstBranchChildren = firstNeed.getTrace().getCraftAttempt().getChildren();

        assertAll(
                () -> assertFalse(result.isFullySatisfied()),
                () -> assertEquals(BlockedReason.BUYING_DISABLED,
                        firstBranchChildren.getFirst().getBlockedReason(), "ordinary failure comes first"),
                () -> assertEquals(terminalReason, firstBranchChildren.get(1).getBlockedReason()),
                () -> assertEquals(terminalReason, firstNeed.getBlockedReason()),
                () -> assertEquals(terminalReason, result.getBlockedReason()),
                () -> assertEquals(List.of(200), rootAttempt.getChildren().stream()
                        .map(ResolvedNeed::getItemId).toList()),
                () -> assertFalse(state.visitedItems.contains(300),
                        "the later owned root sibling must never be visited, even speculatively"),
                () -> assertTrue(state.visitedItems.contains(400)),
                () -> assertTrue(state.visitedItems.contains(500)),
                () -> assertEquals(Map.of(300, 1, 600, quantity), state.inventory),
                () -> assertEquals(0, state.buyCostCopper));
    }

    private static final class RecordingState extends PlanState {
        private final List<Integer> visitedItems = new ArrayList<>();

        private RecordingState(Map<Integer, Integer> inventory) {
            super(inventory);
        }

        @Override
        public int consumeSurplus(int itemId, int wanted) {
            visitedItems.add(itemId);
            return super.consumeSurplus(itemId, wanted);
        }
    }
}
