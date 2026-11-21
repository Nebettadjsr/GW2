package web;

import craft.*;
import org.junit.jupiter.api.Test;
import web.dto.CraftingRowDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The row DTO carries the backend's expected-yield hint so the frontend never recalculates it. */
class CraftingRowMapperExpectedYieldTest {

    @Test
    void forgeRowCarriesSourceExpectedYieldRangeAndEstimatedTotals() {
        Recipe r = Recipe.forgeWithExpectedYield(-5, 10, ExpectedYield.of("18.92", 10, 40), List.of(new Ingredient(11, 1)));
        CraftingSettings s = new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
        Map<Integer, PriceQuote> tp = Map.of(10, new PriceQuote(100, 100));
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(r), Map.of(), Map.of(11, 2), tp, s, Set.of()).get(-5);

        CraftingRowDto row = CraftingRowMapper.toRow(r, res, Map.of(), tp);

        assertNotNull(row.expectedYield());
        assertEquals("MYSTIC_FORGE", row.expectedYield().source());
        assertTrue(row.expectedYield().estimated());
        assertEquals("18.92", row.expectedYield().expectedOutputPerCraft());
        assertEquals(10, row.expectedYield().minOutput());
        assertEquals(40, row.expectedYield().maxOutput());
        assertEquals("37.84", row.expectedYield().expectedSellableUnitsTotal()); // 2 crafts x 18.92, no seed reuse
        assertEquals(20, row.expectedYield().guaranteedSellableUnitsTotal());
    }

    @Test
    void ordinaryRowHasNoHint() {
        Recipe r = new Recipe(1, 10, 1, 0, "Chef", List.of(new Ingredient(11, 1)));
        CraftingRowDto row = CraftingRowMapper.toRow(r, null, Map.of(), Map.of());
        assertNull(row.expectedYield());
    }

    @Test
    void workLimitWithNoAcceptedCraftReportsUnknownEconomicsNotZero() {
        Recipe r = new Recipe(1, 10, 1, 0, "Chef", List.of(new Ingredient(11, 1)));
        CraftResult limited = new CraftResult(10, "Chef", 0, Map.of(), Map.of(), 0, 0, 0, 900, 900, 0, null,
                BlockedReason.WORK_LIMIT, 0, Map.of(), Map.of());
        Map<Integer, PriceQuote> tp = Map.of(10, new PriceQuote(900, 1000));
        CraftingRowDto row = CraftingRowMapper.toRow(r, limited, Map.of(), tp);
        assertEquals("WORK_LIMIT", row.blockedReason());
        assertNull(row.profitCopper());
        assertNull(row.totalProfitCopper());
        assertNull(row.buyCostCopper());
        assertNotNull(row.outputPrice(), "the actual output quote is retained");
    }

    @Test
    void missingSeedWithBuyingOffAndKnownOutputQuoteHasUnknownProfitNotRevenue() {
        // Variable Forge root needing one unowned seed unit; buying is off, so nothing is crafted.
        Recipe r = Recipe.forgeWithExpectedYield(-6, 10, ExpectedYield.of("18.92", 10, 40), List.of(new Ingredient(11, 1)));
        CraftingSettings s = new CraftingSettings(false, false, 0, false, false, false).withMysticForge(true);
        Map<Integer, PriceQuote> tp = Map.of(10, new PriceQuote(100, 103), 11, new PriceQuote(50, 60));
        CraftResult res = new CraftingPlanner().evaluateAll(List.of(r), Map.of(), Map.of(), tp, s, Set.of()).get(-6);

        CraftingRowDto row = CraftingRowMapper.toRow(r, res, Map.of(), tp);

        assertEquals(0, row.craftableCount());
        assertNull(row.profitCopper(), "no actual craft: profit is unknown, never output revenue minus zero");
        assertNull(row.totalProfitCopper());
        assertNotNull(row.outputPrice(), "the known output quote stays visible");
    }

    @Test
    void workLimitAcceptedPrefixKeepsExactFiguresAndTheLimitedReason() {
        Recipe r = new Recipe(1, 10, 1, 0, "Chef", List.of(new Ingredient(11, 1)));
        CraftResult limited = new CraftResult(10, "Chef", 3, Map.of(), Map.of(), 0, 0, 0, 900, 900, 0, null,
                BlockedReason.WORK_LIMIT, 0, Map.of(), Map.of());
        Map<Integer, PriceQuote> tp = Map.of(10, new PriceQuote(900, 1000));
        CraftingRowDto row = CraftingRowMapper.toRow(r, limited, Map.of(), tp);
        assertEquals("WORK_LIMIT", row.blockedReason());
        assertEquals(3, row.craftableCount(), "an accepted prefix is at least N crafts, reported as calculated");
        assertNotNull(row.profitCopper(), "accepted-prefix figures are not discarded");
        assertNotNull(row.outputPrice());
    }
}
