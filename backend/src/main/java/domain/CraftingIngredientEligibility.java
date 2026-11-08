package domain;

/** Unknown crafting uses stay selectable until the available facts rule them out. */
public final class CraftingIngredientEligibility {
    private CraftingIngredientEligibility() {}

    public static boolean isSelectable(boolean material, boolean recipeIngredient,
                                       boolean recipeCatalogComplete, Boolean noMysticForge) {
        return material || recipeIngredient || !recipeCatalogComplete || !Boolean.TRUE.equals(noMysticForge);
    }
}
