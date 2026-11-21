package web;

import craft.Ingredient;
import craft.Recipe;
import repo.ItemRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Display names for alternative recipes, based on the ingredients that differ between paths. */
final class ForgeRecipePaths {
    private ForgeRecipePaths() { }

    static Map<Integer, List<Ingredient>> differences(List<Recipe> recipes) {
        Map<Integer, List<Recipe>> families = new LinkedHashMap<>();
        recipes.forEach(recipe -> families.computeIfAbsent(recipe.outputItemId, ignored -> new ArrayList<>()).add(recipe));
        Map<Integer, List<Ingredient>> result = new LinkedHashMap<>();
        for (var family : families.values()) {
            if (family.size() < 2) continue;
            for (var recipe : family) {
                result.put(recipe.recipeId, recipe.ingredients.stream().filter(ingredient -> family.stream().anyMatch(
                        other -> other.ingredients.stream().noneMatch(candidate -> candidate.itemId == ingredient.itemId
                                && candidate.count == ingredient.count))).toList());
            }
        }
        return result;
    }

    static String label(List<Ingredient> ingredients, Map<Integer, ItemRepository.ItemInfo> items, int outputItemId) {
        if (ingredients == null || ingredients.isEmpty()) return null;
        var equipment = ingredients.stream().map(ingredient -> items.get(ingredient.itemId))
                .filter(item -> item != null && ("Trinket".equals(item.type) || "Back".equals(item.type))).toList();
        var output = items.get(outputItemId);
        if (equipment.size() == 1 && equipment.getFirst().name != null && output != null
                && !equipment.getFirst().name.equals(output.name)) return "From " + equipment.getFirst().name;
        return ingredients.stream().map(ingredient -> {
            var item = items.get(ingredient.itemId);
            String name = item == null || item.name == null ? "Item " + ingredient.itemId : item.name;
            if (item != null && "Trinket".equals(item.type) && item.subtype != null) name += " (" + item.subtype + ")";
            if (item != null && "Armor".equals(item.type) && item.weightClass != null) name += " (" + item.weightClass + ")";
            return (ingredient.count == 1 ? "" : ingredient.count + " × ") + name;
        }).collect(Collectors.joining(" + "));
    }
}
