package web.dto;

import java.util.List;

/** Compact recipe identities whose static dependency graph reaches a matching item name. */
public record CraftingIngredientSearchResponse(List<Integer> recipeIds) {
}
