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
    void knownWalletShowsAllCurrencySpendingAndBreakEven() throws Exception {
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
        assertEquals(row.profitCopper() / 30, c.costs().get(0).breakEvenCopperPerUnit());
    }

    @Test
    void unknownWalletKeepsRecipeAndQuoteVisibleWithoutBreakEvenInventedFromZero() throws Exception {
        CraftingRowDto row = row(Map.of(97487, new PriceQuote(100_000, 120_000), 97102, new PriceQuote(1, 2)), null);
        assertEquals(0, row.craftableCount());
        assertEquals("UNKNOWN", row.currencyCosts().walletState());
        assertNotNull(row.outputPrice());
        assertEquals(30, row.currencyCosts().costs().get(0).perCraft());
    }

    @Test
    void nonPositiveMarginNeverShowsBreakEven() throws Exception {
        CraftingRowDto row = row(Map.of(97487, new PriceQuote(1, 2), 97102, new PriceQuote(500, 600)), Map.of(61, 309));
        assertNull(row.currencyCosts().costs().get(0).breakEvenCopperPerUnit());
    }

    /** PR #375 P1: the first craft consumes an owned intermediate, later crafts need Notes. */
    @Test
    void currencyConsumedOnlyAfterFirstCraftIsStillShownWithTotalAndNoFirstCraftBreakEven() throws Exception {
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
        assertNull(c.costs().get(0).breakEvenCopperPerUnit());
    }
}
