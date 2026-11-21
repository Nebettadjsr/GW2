package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Server-side crafting instruction aggregation: same recipe merges, order is dependency order, root last. */
class ForgeCraftingInstructionsTest {
    private static final int ROOT = 9000, X = 9001, Y = 9002, RED = 9003, YELLOW = 9004, DYE = 9005;

    private static Recipe forge(int id, int out, int outCount, List<Ingredient> in) {
        return new Recipe(-id, out, outCount, 0, "", in, List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private final Recipe red = forge(3, RED, 1, List.of(new Ingredient(YELLOW, 3), new Ingredient(DYE, 2)));
    private final Recipe x = forge(1, X, 1, List.of(new Ingredient(RED, 2)));
    private final Recipe y = forge(2, Y, 1, List.of(new Ingredient(RED, 1)));
    private final Recipe root = forge(4, ROOT, 1, List.of(new Ingredient(X, 1), new Ingredient(Y, 1), new Ingredient(RED, 1)));

    private ForgeProjectPlanner.Project plan() {
        return LegacyForge.planner(List.of(red, x, y, root)).planOutput(root, 1,
                ForgeProjectPlanner.Inventory.shared(Map.of()), ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
    }

    @Test
    void repeatedOccurrencesOfOneRecipeBecomeOneInstructionWithSummedQuantities() {
        var project = plan();
        assertTrue(project.complete(), project.incompleteReason());
        long treeRedCrafts = countCrafts(project.tree(), red.recipeId);
        assertTrue(treeRedCrafts >= 4, "the tree itself still shows Red under X, Y and the root");

        var instructions = ForgeCraftingInstructions.of(project);
        var reds = instructions.stream().filter(i -> i.operation().recipeId() != null
                && i.operation().recipeId() == red.recipeId).toList();
        assertEquals(1, reds.size(), "one merged instruction for Red");
        assertEquals(4, reds.get(0).crafts());
        assertEquals(4, reds.get(0).produced());
        assertEquals(treeRedCrafts, reds.get(0).crafts(), "the instruction sums exactly the tree's crafts");
        assertEquals(Map.of(YELLOW, 12L, DYE, 8L), inputs(reds.get(0)), "direct ingredients are summed");
        assertFalse(reds.get(0).root());
    }

    @Test
    void dependencyOrderKeepsProducersBeforeConsumersAndTheRootLast() {
        var instructions = ForgeCraftingInstructions.of(plan());
        List<Integer> outputs = instructions.stream().map(i -> i.operation().itemId()).toList();
        assertEquals(outputs.size(), outputs.stream().distinct().count(), "no duplicate instruction for an output");
        assertEquals(ROOT, outputs.get(outputs.size() - 1), "the root is last");
        assertTrue(instructions.get(instructions.size() - 1).root());
        assertTrue(outputs.indexOf(RED) < outputs.indexOf(X));
        assertTrue(outputs.indexOf(RED) < outputs.indexOf(Y));
        assertTrue(instructions.size() < countOperations(plan().tree()), "fewer instructions than tree operations");
    }

    @Test
    void differentAssignedCharactersOrStatusesStayOneInstructionEach() {
        // Same recipe, same output, but performed by different characters must not be merged into a false total.
        var ctx = new ForgeProjectPlanner.Context(List.of(
                new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of());
        var project = LegacyForge.planner(List.of(red, x, y, root)).planOutput(root, 1,
                ForgeProjectPlanner.Inventory.shared(Map.of()), ctx, Map.of(), Map.of());
        var instructions = ForgeCraftingInstructions.of(project);
        for (var instruction : instructions) {
            long sameKey = instructions.stream().filter(o -> o.operation().itemId() == instruction.operation().itemId()
                    && java.util.Objects.equals(o.operation().assignedCharacter(), instruction.operation().assignedCharacter())
                    && o.operation().performable() == instruction.operation().performable()
                    && java.util.Objects.equals(o.operation().recipeKnown(), instruction.operation().recipeKnown())).count();
            assertEquals(1, sameKey, "identical recipe/character/status merges; anything else stays separate");
        }
    }

    private static Map<Integer, Long> inputs(ForgeCraftingInstructions.Instruction i) {
        var m = new java.util.TreeMap<Integer, Long>();
        i.ingredients().forEach(in -> m.merge(in.itemId(), in.quantity(), Long::sum));
        return m;
    }

    private static long countCrafts(ForgeProjectPlanner.Node n, int recipeId) {
        long c = n.recipeId() != null && n.recipeId() == recipeId ? n.crafts() : 0;
        for (var child : n.children()) c += countCrafts(child, recipeId);
        return c;
    }

    private static long countOperations(ForgeProjectPlanner.Node n) {
        long c = n.recipeId() != null && n.crafts() > 0 ? 1 : 0;
        for (var child : n.children()) c += countOperations(child);
        return c;
    }
}
