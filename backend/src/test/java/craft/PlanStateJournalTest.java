package craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-PERF-001 / UD-005: the planner no longer deep-copies {@link PlanState} before every
 * speculative craft attempt; it mutates the live state and undoes the attempt if it loses. These
 * tests pin the property the whole optimization rests on - that undoing a speculation restores
 * exactly the state that existed before it, and that a winning speculation can be set aside and
 * re-applied unchanged - independently of the planner that uses it.
 */
class PlanStateJournalTest {

    private static PlanState stateWithEverything() {
        PlanState state = new PlanState(
                new HashMap<>(Map.of(100, 10, 200, 5)),
                new HashMap<>(Map.of(300, 7)),
                Map.of("Hero", new HashMap<>(Map.of(400, 3))));
        state.addBuyCost(41);
        state.setDailyLeft(600, 1);
        return state;
    }

    private static String snapshot(PlanState s) {
        return "inv=" + new java.util.TreeMap<>(s.inventory)
                + " bound=" + new java.util.TreeMap<>(s.boundInventory)
                + " charBound=" + new java.util.TreeMap<>(s.characterBoundInventory)
                + " visiting=" + new java.util.TreeSet<>(s.visiting)
                + " daily=" + new java.util.TreeMap<>(s.dailyLeft)
                + " buyCost=" + s.buyCostCopper;
    }

    @Test
    void rollbackRestoresEveryKindOfChange() {
        PlanState state = stateWithEverything();
        String before = snapshot(state);

        int mark = state.mark();

        state.consumeInventoryWithBinding(100, 4, "Hero");   // sellable pool
        state.consumeInventoryWithBinding(300, 7, "Hero");   // account-bound pool, emptied
        state.consumeInventoryWithBinding(400, 2, "Hero");   // character-bound pool
        state.addBuyCost(1000);
        state.setDailyLeft(600, 0);
        state.beginVisiting(700);

        assertFalse(before.equals(snapshot(state)), "the speculative changes should be visible first");

        state.rollbackTo(mark);

        assertEquals(before, snapshot(state));
    }

    @Test
    void rollbackRestoresAnItemThatWasFullyConsumed() {
        PlanState state = new PlanState(new HashMap<>(Map.of(100, 3)));
        int mark = state.mark();

        assertEquals(3, state.consumeInventory(100, 3));
        assertFalse(state.inventory.containsKey(100), "a fully consumed item is removed, not left at 0");

        state.rollbackTo(mark);

        assertEquals(3, state.inventory.get(100));
    }

    @Test
    void nestedSpeculationsRollBackIndependently() {
        PlanState state = stateWithEverything();
        String before = snapshot(state);

        int outer = state.mark();
        state.consumeInventory(100, 2);
        String afterOuter = snapshot(state);

        int inner = state.mark();
        state.consumeInventory(100, 3);
        state.addBuyCost(5);
        state.rollbackTo(inner);

        assertEquals(afterOuter, snapshot(state), "undoing the inner attempt must keep the outer one");

        state.rollbackTo(outer);
        assertEquals(before, snapshot(state));
    }

    @Test
    void capturedDeltaReappliesTheWinningAttemptExactly() {
        PlanState state = stateWithEverything();
        int mark = state.mark();

        // Candidate A: the one that will "win".
        state.consumeInventoryWithBinding(100, 4, "Hero");
        state.consumeInventoryWithBinding(400, 3, "Hero");
        state.addBuyCost(70);
        String winning = snapshot(state);
        PlanState.Delta bestEffect = state.captureDelta(mark);
        state.rollbackTo(mark);

        // Candidate B: tried from the same starting point, then discarded.
        state.consumeInventoryWithBinding(200, 5, "Hero");
        state.addBuyCost(9999);
        state.rollbackTo(mark);

        state.applyDelta(bestEffect);

        assertEquals(winning, snapshot(state));
    }

    @Test
    void anAppliedDeltaIsItselfUndoable() {
        PlanState state = stateWithEverything();
        String before = snapshot(state);

        int outer = state.mark();
        int inner = state.mark();
        state.consumeInventory(100, 6);
        state.addBuyCost(12);
        PlanState.Delta effect = state.captureDelta(inner);
        state.rollbackTo(inner);
        state.applyDelta(effect);

        state.rollbackTo(outer);

        assertEquals(before, snapshot(state), "an enclosing speculation must still be able to undo a re-applied delta");
    }

    @Test
    void commitKeepsChangesAndPreventsUndoingThem() {
        PlanState state = stateWithEverything();

        int mark = state.mark();
        state.consumeInventory(100, 4);
        state.addBuyCost(25);
        String committed = snapshot(state);

        state.commitTo(mark);
        assertEquals(committed, snapshot(state), "committing must not change any value");

        // A later speculation on top of a committed one still rolls back to the committed values.
        int later = state.mark();
        state.consumeInventory(200, 5);
        state.rollbackTo(later);
        assertEquals(committed, snapshot(state));
    }

    @Test
    void visitingMarksRollBackToAbsentOrPresentAsAppropriate() {
        PlanState state = new PlanState(new HashMap<>(Map.of(100, 1)));
        state.beginVisiting(10);
        assertTrue(state.isVisiting(10));

        int mark = state.mark();
        state.beginVisiting(20);
        state.endVisiting(10);
        assertTrue(state.isVisiting(20));
        assertFalse(state.isVisiting(10));

        state.rollbackTo(mark);

        assertTrue(state.isVisiting(10), "a mark removed during the attempt must come back");
        assertFalse(state.isVisiting(20), "a mark added during the attempt must be gone");
    }

    @Test
    void copyConstructorProducesAnIndependentStateWithItsOwnHistory() {
        PlanState original = stateWithEverything();
        PlanState copy = new PlanState(original);

        assertEquals(snapshot(original), snapshot(copy));

        String originalBefore = snapshot(original);
        copy.consumeInventory(100, 10);
        copy.addBuyCost(500);

        assertEquals(originalBefore, snapshot(original), "mutating the copy must not touch the original");
    }
}
