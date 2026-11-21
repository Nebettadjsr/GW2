package craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One instruction per recipe and crafting character, using the quantities of the already-resolved plan. */
public final class ForgeCraftingInstructions {
    private ForgeCraftingInstructions() { }

    public record Input(int itemId, long quantity) { }
    public record Instruction(ForgeProjectPlanner.Node operation, boolean root, long crafts, long produced,
                              long toAcquire, long newSurplus, List<Input> ingredients) { }
    private record Occurrence(ForgeProjectPlanner.Node node, boolean root, int rank) { }
    private record Key(Integer recipeId, int itemId, String character, Boolean recipeKnown,
                       boolean performable, String cutoff) { }

    public static List<Instruction> of(ForgeProjectPlanner.Project project) {
        Map<Integer, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < project.steps().size(); index++) {
            ranks.put(project.steps().get(index).recipeId(), index * 2);
        }
        List<Occurrence> occurrences = new ArrayList<>();
        collect(project.tree(), true, Integer.MAX_VALUE - 1, ranks, occurrences);
        occurrences.sort(Comparator.comparingInt((Occurrence occurrence) -> phase(occurrence.node()))
                .thenComparingInt(Occurrence::rank));
        Map<Key, Group> groups = new LinkedHashMap<>();
        try {
            for (var occurrence : occurrences) {
                var node = occurrence.node();
                Key key = new Key(node.recipeId(), node.itemId(), node.assignedCharacter(), node.recipeKnown(),
                        node.performable(), node.cutoff());
                groups.computeIfAbsent(key, ignored -> new Group(node)).include(occurrence);
            }
            return groups.values().stream().map(Group::instruction).toList();
        } catch (ArithmeticException overflow) {
            // A stopped, out-of-range plan can contain individually safe operations whose combined total is not safe.
            return occurrences.stream().map(occurrence -> {
                var node = occurrence.node();
                return new Instruction(node, occurrence.root(), node.crafts(), node.produced(), node.toAcquire(), node.newSurplus(),
                        node.children().stream().map(input -> new Input(input.itemId(), input.needed())).toList());
            }).toList();
        }
    }

    private static void collect(ForgeProjectPlanner.Node node, boolean root, int parentRank,
                                Map<Integer, Integer> ranks, List<Occurrence> occurrences) {
        if (node == null) return;
        int rank = node.recipeId() == null ? parentRank - 1 : ranks.getOrDefault(node.recipeId(), parentRank);
        for (var child : node.children()) collect(child, false, rank, ranks, occurrences);
        boolean crafting = node.recipeId() != null && node.crafts() > 0
                && (node.source() != RecipeSource.VENDOR || node.hint() != null);
        boolean estimated = node.recipeId() == null && node.toAcquire() > 0 && node.hint() != null
                && !node.children().isEmpty();
        if (crafting || estimated) occurrences.add(new Occurrence(node, root, rank));
    }

    private static int phase(ForgeProjectPlanner.Node node) {
        return node.source() == RecipeSource.VENDOR ? 0 : node.recipeId() == null ? 1 : 2;
    }

    private static final class Group {
        private final ForgeProjectPlanner.Node operation;
        private final Map<Integer, Long> inputs = new LinkedHashMap<>();
        private boolean root;
        private long crafts;
        private long produced;
        private long toAcquire;
        private long newSurplus;

        private Group(ForgeProjectPlanner.Node operation) { this.operation = operation; }

        private void include(Occurrence occurrence) {
            var node = occurrence.node();
            root |= occurrence.root();
            crafts = ForgeProjectPlanner.add(crafts, node.crafts());
            produced = ForgeProjectPlanner.add(produced, node.produced());
            toAcquire = ForgeProjectPlanner.add(toAcquire, node.toAcquire());
            newSurplus = ForgeProjectPlanner.add(newSurplus, node.newSurplus());
            for (var input : node.children()) inputs.merge(input.itemId(), input.needed(), ForgeProjectPlanner::add);
        }

        private Instruction instruction() {
            return new Instruction(operation, root, crafts, produced, toAcquire, newSurplus,
                    inputs.entrySet().stream().map(input -> new Input(input.getKey(), input.getValue())).toList());
        }
    }
}
