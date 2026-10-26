package craft;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static craft.CraftTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Investigation #116: normative counterexamples, intentionally separate from default tests. */
class InvestigationCounterexamplesIT {
    @Test void ownedBoundInputMakesCraftingCheaperThanBuyingTheIntermediate() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 1)));
        Recipe intermediate = recipe(2, 1002, List.of(ingredient(1003, 1)));
        var settings = new CraftingSettings(true, true, 1000, false, false, false);
        var context = context(Map.of(1001, List.of(root), 1002, List.of(intermediate)),
                Map.of(1002, quote(50, 50), 1003, quote(100, 100)), settings, Set.of(1, 2));
        var result = new CraftingResolver().resolveOneCraft(root, context,
                stateWithBoundInventory(Map.of(), Map.of(1003, 1)));
        // A bound owned input has zero sale opportunity cost; the 50c purchase is avoidable.
        assertEquals(0, result.getEffectiveCostCopper(), "Must evaluate actual recursive cost before pruning");
        assertEquals(AcquisitionMode.CRAFT, result.getRoot().getChildren().get(0).getMode());
    }

    @Test void intermediateBatchSurplusCanSupplyTheNextCraft() {
        Recipe root = recipe(1, 1001, List.of(ingredient(1002, 5)));
        Recipe intermediate = recipe(2, 1002, 6, List.of(ingredient(1003, 1)));
        var context = context(Map.of(1001, List.of(root), 1002, List.of(intermediate)),
                Map.of(), new CraftingSettings(true, false, 0, false, false, false), Set.of(1, 2));
        var state = stateWithBoundInventory(Map.of(), Map.of(1003, 5));
        int complete = 0;
        for (int i = 0; i < 6; i++) {
            int mark = state.mark();
            var result = new CraftingResolver().resolveOneCraft(root, context, state);
            if (result.getQtyBlocked() > 0) { state.rollbackTo(mark); break; }
            complete++;
        }
        // Five batches produce 30 intermediate items, enough for six roots requiring five each.
        assertEquals(6, complete, "Produced surplus must be retained across successful crafts");
    }
}
