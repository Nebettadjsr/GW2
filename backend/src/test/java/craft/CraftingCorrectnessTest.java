package craft;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static craft.CraftTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftingCorrectnessTest {
    private static final CraftingSettings OWN_ONLY = new CraftingSettings(true, false, 0, false, false, false);

    @Test void ownedBoundInputMustNotBePrunedByItsPurchasePrice() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 1)));
        Recipe sub = recipe(2, 1002, List.of(ingredient(1003, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(sub)),
                Map.of(1002, quote(50, 50), 1003, quote(100, 100)),
                new CraftingSettings(true, true, 1000, false, false, false), Set.of(1, 2));
        var result = new CraftingResolver().resolveOneCraft(root, ctx,
                stateWithBoundInventory(Map.of(), Map.of(1003, 1)));
        assertEquals(0, result.getEffectiveCostCopper());
        assertEquals(AcquisitionMode.CRAFT, result.getRoot().getChildren().getFirst().getMode());
    }

    @Test void surplusSuppliesSixRootsFromFiveBatchesAndIsNotRevalued() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5)));
        Recipe sub = recipe(2, 1002, 6, List.of(ingredient(1003, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(sub)),
                Map.of(1003, quote(10, 10), 1002, quote(500, 500)), OWN_ONLY, Set.of(1, 2));
        var state = new PlanState(Map.of(1003, 5));
        int total = 0;
        for (int i = 0; i < 6; i++) {
            var result = new CraftingResolver().resolveOneCraft(root, ctx, state);
            assertEquals(0, result.getQtyBlocked());
            total += result.getEffectiveCostCopper();
            state.commitTo(0);
        }
        assertEquals(50, total, "Charge only the five consumed raw materials, not reused surplus");
        assertTrue(new CraftingResolver().resolveOneCraft(root, ctx, state).getQtyBlocked() > 0);
    }

    @Test void producedSurplusIsUsableEvenWhenOwnedMaterialsAreDisabled() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5)));
        Recipe sub = recipe(2, 1002, 6, List.of(ingredient(1003, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(sub)), Map.of(1003, quote(10, 10)),
                new CraftingSettings(false, true, 1000, false, false, false), Set.of(1, 2));
        var state = emptyState();
        for (int i = 0; i < 6; i++) assertEquals(0, new CraftingResolver().resolveOneCraft(root, ctx, state).getQtyBlocked());
        assertEquals(50, state.buyCostCopper);
    }

    @Test void actualBatchCostAndInventoryChooseTheValidRecipe() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5)));
        Recipe unavailable = recipe(2, 1002, List.of(ingredient(1004, 1)));
        Recipe batch = recipe(3, 1002, 6, List.of(ingredient(1003, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(unavailable, batch)),
                Map.of(1004, quote(1, 1), 1003, quote(4, 4)), OWN_ONLY, Set.of(1, 2, 3));
        var state = stateWithBoundInventory(Map.of(), Map.of(1003, 1));
        assertEquals(0, new CraftingResolver().resolveOneCraft(root, ctx, state).getQtyBlocked());
        assertEquals(1, state.consumeSurplus(1002, 5));
    }

    @Test void surplusIsSharedAcrossBranchesAndRolledBackWithFailedRoot() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5), ingredient(1005, 1), ingredient(9999, 1)));
        Recipe batch = recipe(2, 1002, 6, List.of(ingredient(1003, 1)));
        Recipe branch = recipe(3, 1005, List.of(ingredient(1002, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(batch), 1005, List.of(branch)),
                Map.of(), OWN_ONLY, Set.of(1, 2, 3));
        var state = stateWithBoundInventory(Map.of(), Map.of(1003, 1));
        var result = new CraftingResolver().resolveOneCraft(root, ctx, state);
        assertTrue(result.getQtyBlocked() > 0);
        assertEquals(Map.of(1003, 1), state.boundInventory);
        assertEquals(0, state.consumeSurplus(1002, 10));
        assertTrue(state.visiting.isEmpty());
        assertEquals(0, state.buyCostCopper);
    }

    @Test void surplusSurvivesDeltaSelectionButNotRollback() {
        var state = emptyState();
        int mark = state.mark();
        state.addSurplus(7, 6);
        state.consumeSurplus(7, 5);
        var delta = state.captureDelta(mark);
        state.rollbackTo(mark);
        state.applyDelta(delta);
        assertEquals(1, state.consumeSurplus(7, 10));
        state.rollbackTo(mark);
        assertEquals(0, state.consumeSurplus(7, 10));
    }

    @Test void recipesAreComparedForRequestedQuantityNotOneBatchPrice() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5)));
        Recipe unit = recipe(2, 1002, List.of(ingredient(1004, 1)));
        Recipe batch = recipe(3, 1002, 6, List.of(ingredient(1003, 1)));
        var ctx = context(Map.of(1001, List.of(root), 1002, List.of(unit, batch)),
                Map.of(1004, quote(1, 1), 1003, quote(4, 4)),
                new CraftingSettings(false, true, 100, false, false, false), Set.of(1, 2, 3));
        var state = emptyState();
        assertEquals(4, new CraftingResolver().resolveOneCraft(root, ctx, state).getEffectiveCostCopper());
        assertEquals(4, state.buyCostCopper, "Rejected unit recipe must not spend money");
        assertEquals(1, state.consumeSurplus(1002, 10));
    }
}
