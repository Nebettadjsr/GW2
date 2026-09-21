package craft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecipeTreeBuilder {

    public Node buildTree(Recipe recipe, PlannerContext ctx) {
        if (recipe == null) {
            return new Node(-1, 0, "no-recipe", List.of());
        }

        Set<Integer> visiting = new HashSet<>();
        List<Node> children = new ArrayList<>();

        for (Ingredient ing : recipe.ingredients) {
            children.add(buildIngredientNode(ing.itemId, ing.count, ctx, visiting));
        }

        return new Node(
                recipe.outputItemId,
                recipe.outputCount,
                "craft",
                children
        );
    }

    private Node buildIngredientNode(
            int itemId,
            int qtyNeeded,
            PlannerContext ctx,
            Set<Integer> visiting
                                    ) {
        if (visiting.contains(itemId)) {
            return new Node(itemId, qtyNeeded, "cycle", List.of());
        }

        List<Recipe> producing = ctx.recipesByOutput.get(itemId);

        if (producing == null || producing.isEmpty()) {
            return new Node(itemId, qtyNeeded, "base", List.of());
        }

        Recipe recipe = producing.get(0);

        visiting.add(itemId);
        try {
            int times = ceilDiv(qtyNeeded, recipe.outputCount);

            List<Node> children = new ArrayList<>();
            for (Ingredient ing : recipe.ingredients) {
                children.add(buildIngredientNode(
                        ing.itemId,
                        ing.count * times,
                        ctx,
                        visiting
                                                ));
            }

            return new Node(itemId, qtyNeeded, "craft", children);
        } finally {
            visiting.remove(itemId);
        }
    }

    private int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}