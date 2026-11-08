package domain;

import org.junit.jupiter.api.Test;

import static domain.CraftingIngredientEligibility.isSelectable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingIngredientEligibilityTest {
    @Test
    void hidesOnlyAnExplicitlyExcludedItemWithCompleteRecipeCoverage() {
        assertFalse(isSelectable(false, false, true, true));
        assertTrue(isSelectable(false, false, true, false));
        assertTrue(isSelectable(false, false, true, null));
        assertTrue(isSelectable(false, false, false, true));
    }

    @Test
    void preservesEveryStorageMaterialAndActualRecipeIngredient() {
        assertTrue(isSelectable(true, false, true, true));
        assertTrue(isSelectable(false, true, true, true));
    }
}
