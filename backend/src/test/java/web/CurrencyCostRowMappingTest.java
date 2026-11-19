package web;

import craft.CraftResult;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.CurrencyRequirement;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import web.dto.CraftingRowDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #372: the row carries exact unpriced currency spending and a conditional gold margin. */
class CurrencyCostRowMappingTest {

    private static final Recipe JADE = new Recipe(13839, 97487, 1, 400, "Artificer",
            List.of(new Ingredient(97102, 4)), List.of(new CurrencyRequirement(61, 30)));
    private static final CraftingSettings OWN = new CraftingSettings(true, false, 0, false, false, true);

    private static CraftingRowDto row(Map<Integer, PriceQuote> tp, Map<Integer, Integer> wallet) throws Exception {
        CraftResult r = new CraftingPlanner().withWallet(wallet)
                .evaluateAll(List.of(JADE), Map.of(97102, 40), Map.of(), tp, OWN, Set.of(13839)).get(13839);
        return CraftingRowMapper.toRow(JADE, r, Map.of(), tp);
    }

    @Test
    void knownWalletShowsAllCurrencySpendingOnly() throws Exception {
        CraftingRowDto row = row(Map.of(97487, new PriceQuote(100_000, 120_000), 97102, new PriceQuote(1, 2)),
                Map.of(61, 309));
        assertEquals(10, row.craftableCount());
        var c = row.currencyCosts();
        assertNotNull(c);
        assertEquals("KNOWN", c.walletState());
        assertTrue(c.goldMarginIsConditional());
        assertEquals(1, c.costs().size());
        assertEquals(61, c.costs().get(0).currencyId());
        assertEquals("Research Note", c.costs().get(0).name());
        assertEquals(30, c.costs().get(0).perCraft());
        assertEquals(300, c.costs().get(0).total());
        assertTrue(row.profitCopper() > 0);
    }

    @Test
    void unknownWalletKeepsRecipeAndQuoteVisibleWithoutInventedValue() throws Exception {
        CraftingRowDto row = row(Map.of(97487, new PriceQuote(100_000, 120_000), 97102, new PriceQuote(1, 2)), null);
        assertEquals(0, row.craftableCount());
        assertEquals("UNKNOWN", row.currencyCosts().walletState());
        assertNotNull(row.outputPrice());
        assertEquals(30, row.currencyCosts().costs().get(0).perCraft());
    }

    /** PR #375 P1: the first craft consumes an owned intermediate, later crafts need Notes. */
    @Test
    void currencyConsumedOnlyAfterFirstCraftIsStillShownWithTotal() throws Exception {
        Recipe top = new Recipe(1, 1000, 1, 0, "Artificer", List.of(new Ingredient(2000, 1)));
        Recipe inter = new Recipe(2, 2000, 1, 0, "Artificer", List.of(new Ingredient(3000, 1)),
                List.of(new CurrencyRequirement(61, 30)));
        Map<Integer, PriceQuote> tp = Map.of(1000, new PriceQuote(10_000, 12_000));
        CraftResult r = new CraftingPlanner().withWallet(Map.of(61, 100))
                .evaluateAll(List.of(top, inter), Map.of(2000, 1, 3000, 10), Map.of(), tp, OWN, Set.of(1, 2)).get(1);
        assertTrue(r.craftableCount >= 2, "second craft must be possible via the intermediate recipe");
        assertTrue(r.currencyPerCraft.isEmpty(), "first craft consumes the owned intermediate");
        CraftingRowDto row = CraftingRowMapper.toRow(top, r, Map.of(), tp);
        var c = row.currencyCosts();
        assertNotNull(c, "total currency must not disappear");
        assertEquals("KNOWN", c.walletState());
        assertEquals(1, c.costs().size());
        assertEquals(0, c.costs().get(0).perCraft());
        assertEquals(30 * (r.craftableCount - 1), c.costs().get(0).total());
    }

    /** Approved estimate: rows carry only the compact amounts; the selected detail adds both actual recipes. */
    @Test
    void estimateVariantsAppearOnlyInSelectedDetailAndAgreeWithTheTableRow() {
        Recipe a = new Recipe(15120, 104934, 1, 0, "Artificer", List.of(new Ingredient(1001, 4)));
        Recipe b = new Recipe(15131, 104934, 1, 0, "Artificer", List.of(new Ingredient(2001, 4)));
        Map<Integer, PriceQuote> tp = Map.of(97487, new PriceQuote(100_000, 120_000), 97102, new PriceQuote(1, 2),
                1001, new PriceQuote(100, 100));
        CraftResult r = new CraftingPlanner().withWallet(Map.of(61, 90)).evaluateAll(List.of(JADE, a, b),
                Map.of(97102, 40), Map.of(), tp, OWN, Set.of(13839, 15120, 15131)).get(13839);
        CraftingRowDto table = CraftingRowMapper.toRow(JADE, r, Map.of(), tp);
        CraftingRowDto detail = CraftingRowMapper.toRow(JADE, r, Map.of(), tp, true);
        var te = table.currencyCosts().estimate();
        var de = detail.currencyCosts().estimate();
        assertEquals(90, te.totalNotes());
        assertEquals(240, te.totalCopper());
        assertTrue(te.variants().isEmpty(), "no recipe comparison is repeated across table rows");
        assertEquals(te.totalCopper(), de.totalCopper());
        assertEquals(table.profitCopper(), detail.profitCopper());
        assertEquals(2, de.variants().size());
        assertTrue(de.variants().get(0).basis());
        assertEquals(400L, de.variants().get(0).batchCostCopper());
        assertEquals(3L, de.variants().get(0).perNoteCopperCeil());
        assertNull(de.variants().get(1).batchCostCopper());
        assertEquals(false, table.currencyCosts().goldMarginIsConditional());
    }
}
