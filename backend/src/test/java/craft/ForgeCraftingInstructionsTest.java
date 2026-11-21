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
    void sameCharacterOccurrencesMergeWhileDifferentCharactersStaySeparate() {
        // Ann's two Red occurrences merge into one instruction; Bob's occurrence has another assigned character and stays separate.
        var node = (java.util.function.Function<String, ForgeProjectPlanner.Node>) character -> new ForgeProjectPlanner.Node(
                RED, 2, 0, 0, 2, 0, red.recipeId, RecipeSource.MYSTIC_FORGE, 2, 0, "", null, List.of(), true, true, character, null, 2, 0);
        var ann = node.apply("Ann");
        var bob = node.apply("Bob");
        var rootNode = new ForgeProjectPlanner.Node(ROOT, 1, 0, 0, 1, 0, root.recipeId, RecipeSource.MYSTIC_FORGE, 1, 0, "", null,
                List.of(ann, bob, ann), true, true, null, null, 1, 0);
        var project = new ForgeProjectPlanner.Project(root.recipeId, ROOT, 1, 1,
                List.of(), List.of(), List.of(), rootNode, 0, List.of(), true, null, null, null, null, List.of(),
                ForgeSettings.DEFAULT, null, null, 0, null);
        var instructions = ForgeCraftingInstructions.of(project);
        var reds = instructions.stream().filter(i -> i.operation().itemId() == RED).toList();
        assertEquals(2, reds.size(), "Ann's two occurrences merge, Bob's stays separate");
        var annInstruction = reds.stream().filter(i -> "Ann".equals(i.operation().assignedCharacter())).findFirst().orElseThrow();
        var bobInstruction = reds.stream().filter(i -> "Bob".equals(i.operation().assignedCharacter())).findFirst().orElseThrow();
        assertEquals(4, annInstruction.crafts());
        assertEquals(2, bobInstruction.crafts());
    }

    private ForgeProjectPlanner.Project projectOf(ForgeProjectPlanner.Node rootNode) {
        return new ForgeProjectPlanner.Project(root.recipeId, ROOT, 1, 1,
                List.of(), List.of(), List.of(), rootNode, 0, List.of(), true, null, null, null, null, List.of(),
                ForgeSettings.DEFAULT, null, null, 0, null);
    }

    private ForgeProjectPlanner.Node redNode(long crafts, Boolean known, boolean performable, String cutoff) {
        return new ForgeProjectPlanner.Node(RED, crafts, 0, 0, crafts, 0, red.recipeId, RecipeSource.MYSTIC_FORGE, crafts, 0, "",
                cutoff, List.of(), known, performable, "Ann", null, crafts, 0);
    }

    @Test
    void combinedGroupOverflowFallsBackToIndividuallySafeOccurrencesWithoutClamping() {
        long half = Long.MAX_VALUE / 2 + 1; // each occurrence is individually safe; their sum is not
        var one = redNode(half, true, true, null);
        var rootNode = new ForgeProjectPlanner.Node(ROOT, 1, 0, 0, 1, 0, root.recipeId, RecipeSource.MYSTIC_FORGE, 1, 0, "", null,
                List.of(one, one), true, true, null, null, 1, 0);
        var instructions = ForgeCraftingInstructions.of(projectOf(rootNode));
        var reds = instructions.stream().filter(i -> i.operation().itemId() == RED).toList();
        assertEquals(2, reds.size(), "the unsafe combined group is not merged");
        assertTrue(reds.stream().allMatch(i -> i.crafts() == half && i.produced() == half), "no clamped or wrapped totals");
    }

    @Test
    void differentKnowledgePerformableAndCutoffStatusesNeverMerge() {
        var rootNode = new ForgeProjectPlanner.Node(ROOT, 1, 0, 0, 1, 0, root.recipeId, RecipeSource.MYSTIC_FORGE, 1, 0, "", null,
                List.of(redNode(1, true, true, null), redNode(2, false, true, null), redNode(4, true, false, null),
                        redNode(8, true, true, "Recipe display limit reached"), redNode(16, true, true, null)),
                true, true, null, null, 1, 0);
        var reds = ForgeCraftingInstructions.of(projectOf(rootNode)).stream().filter(i -> i.operation().itemId() == RED).toList();
        assertEquals(4, reds.size(), "known/unknown, performable/not and cutoff each stay separate; identical ones merge");
        assertEquals(java.util.Set.of(17L, 2L, 4L, 8L), reds.stream().map(ForgeCraftingInstructions.Instruction::crafts)
                .collect(java.util.stream.Collectors.toSet()));
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
