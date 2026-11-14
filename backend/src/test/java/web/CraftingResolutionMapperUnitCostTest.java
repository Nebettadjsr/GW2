package web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingResolutionMapperUnitCostTest {

    @Test
    void exactDivisionIsNotApproximate() {
        assertEquals(11, CraftingResolutionMapper.unitCost(1353, 123));
        assertFalse(CraftingResolutionMapper.isApproximate(1353, 123));
    }

    @Test
    void nonDivisibleTotalRoundsHalfUpAndIsApproximate() {
        assertEquals(30, CraftingResolutionMapper.unitCost(1234, 41));
        assertTrue(CraftingResolutionMapper.isApproximate(1234, 41));
        assertEquals(3, CraftingResolutionMapper.unitCost(5, 2), "tie rounds up");
        assertEquals(2, CraftingResolutionMapper.unitCost(5, 3));
    }

    @Test
    void knownZeroStaysZeroAndSingularKeepsTheTotal() {
        assertEquals(0, CraftingResolutionMapper.unitCost(0, 7));
        assertFalse(CraftingResolutionMapper.isApproximate(0, 7));
        assertEquals(1234, CraftingResolutionMapper.unitCost(1234, 1));
        assertFalse(CraftingResolutionMapper.isApproximate(1234, 1));
    }

    @Test
    void missingTotalOrNonPositiveQuantityHasNoUnitValue() {
        assertNull(CraftingResolutionMapper.unitCost(null, 5));
        assertFalse(CraftingResolutionMapper.isApproximate(null, 5));
        assertNull(CraftingResolutionMapper.unitCost(100, 0));
        assertNull(CraftingResolutionMapper.unitCost(100, -2));
        assertFalse(CraftingResolutionMapper.isApproximate(100, 0));
    }
}
