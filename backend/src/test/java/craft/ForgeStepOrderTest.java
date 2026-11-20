package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Executable step order for Research Note consumers (nested and root) fed by a self-seeded surplus supplier. */
class ForgeStepOrderTest {
    private static final int LOGS = 19722, PRR = 104934, NOTE = 61;

    private final Recipe promotion = Recipe.forgeWithExpectedYield(-32, LOGS, ExpectedYield.of("86.34", 40, 200),
            List.of(new Ingredient(500, 250), new Ingredient(LOGS, 1), new Ingredient(501, 5), new Ingredient(20796, 1)));
    private final Recipe notes = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(new Ingredient(LOGS, 30)));
    private final ForgeProjectPlanner.Context ctx = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Fixture", Map.of("Artificer", 500))), Set.of(15131, 98765));

    private List<Integer> order(Recipe root, Recipe... extra) {
        var all = new java.util.ArrayList<>(List.of(promotion, notes));
        all.addAll(List.of(extra));
        var p = new ForgeProjectPlanner(all).planOutput(root, 1, ForgeProjectPlanner.Inventory.shared(Map.of()), ctx,
                Map.of(NOTE, 0L), Map.of());
        assertTrue(p.complete(), p.incompleteReason());
        return p.steps().stream().map(ForgeProjectPlanner.CraftStep::outputItemId).toList();
    }

    @Test
    void nestedNoteConsumerFollowsLogsAndNotesAndPrecedesRoot() {
        Recipe consumer = new Recipe(98765, 9000002, 1, 0, "Artificer", List.of(),
                List.of(new CurrencyRequirement(NOTE, 30)), RecipeSource.ORDINARY);
        Recipe root = new Recipe(-21, 9000003, 1, 0, "",
                List.of(new Ingredient(9000002, 1), new Ingredient(LOGS, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        assertEquals(List.of(LOGS, PRR, 9000002, 9000003), order(root, consumer));
    }

    @Test
    void rootNoteConsumerStillFollowsLogsAndNotes() {
        Recipe root = new Recipe(-21, 9000003, 1, 0, "", List.of(new Ingredient(LOGS, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)), RecipeSource.MYSTIC_FORGE);
        assertEquals(List.of(LOGS, PRR, 9000003), order(root));
    }
}
