package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #372: Piece of Dragon Jade style recipes with a typed Research Note (currency 61) requirement. */
class CraftingPlannerCurrencyRequirementTest {

    private static final int NOTE = 61;

    /** Recipe 13839: 4 jade, 30 Research Notes, 2 ectoplasm, 5 orichalcum ingots -> 1 item 97487. */
    private static final Recipe JADE = new Recipe(13839, 97487, 1, 400, "Artificer",
            List.of(ingredient(97102, 4), ingredient(19721, 2), ingredient(19685, 5)),
            List.of(new CurrencyRequirement(NOTE, 30)));

    private static final Map<Integer, Integer> SCREENSHOT_STOCK = Map.of(97102, 103, 19721, 62, 19685, 57);
    private static final Map<Integer, PriceQuote> TP = Map.of(97487, quote(5000, 6000));
    private static final CraftingSettings OWN_ONLY = new CraftingSettings(true, false, 0, false, false, true);

    private static CraftResult jade(Map<Integer, Integer> wallet) {
        return new CraftingPlanner().withWallet(wallet)
                .evaluateAll(List.of(JADE), SCREENSHOT_STOCK, Map.of(), TP, OWN_ONLY, Set.of(13839)).get(13839);
    }

    @Test
    void screenshotStocksAllowTenCraftsAndConsumeThreeHundredNotes() {
        CraftResult r = jade(Map.of(NOTE, 309));
        assertEquals(10, r.craftableCount);
        assertEquals(Map.of(NOTE, 30), r.currencyPerCraft);
        assertEquals(Map.of(NOTE, 300), r.currencyTotal);
        assertEquals("KNOWN", r.walletState);
    }

    @Test
    void twentyNineNotesAllowNoCraftButKeepTheQuoteVisible() {
        CraftResult r = jade(Map.of(NOTE, 29));
        assertEquals(0, r.craftableCount);
        assertEquals(Map.of(NOTE, 30), r.currencyPerCraft);
    }

    @Test
    void missingWalletIsUnknownNeitherZeroNorUnlimited() {
        CraftResult r = jade(null);
        assertEquals(0, r.craftableCount);
        assertEquals("UNKNOWN", r.walletState);
        assertEquals(Map.of(NOTE, 30), r.currencyPerCraft);
    }

    @Test
    void fullyPublishedEmptyWalletIsKnownZeroNotUnknown() {
        CraftResult r = jade(Map.of());
        assertEquals(0, r.craftableCount);
        assertEquals("KNOWN", r.walletState);
        assertEquals(craft.BlockedReason.INSUFFICIENT_CURRENCY, r.blockedReason);
    }

    @Test
    void currencyIdIsNeverTreatedAsItemId() {
        // Item 61 stock must not satisfy currency 61.
        Map<Integer, Integer> stock = new java.util.HashMap<>(SCREENSHOT_STOCK);
        stock.put(NOTE, 1000);
        CraftResult r = new CraftingPlanner().withWallet(Map.of())
                .evaluateAll(List.of(JADE), stock, Map.of(), TP, OWN_ONLY, Set.of(13839)).get(13839);
        assertEquals(0, r.craftableCount);
    }

    @Test
    void nestedAndRepeatedCraftsShareOneWalletPool() {
        // Parent needs 2 intermediates (each 30 notes) plus 30 notes itself: 90 per craft.
        Recipe inter = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Recipe parent = new Recipe(1, 100, 1, 0, "Artificer", List.of(ingredient(200, 2)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Map<Integer, Integer> stock = Map.of(300, 100);
        Map<Integer, PriceQuote> tp = Map.of(100, quote(5000, 6000));
        for (int[] c : new int[][]{{89, 0}, {90, 1}, {185, 2}}) {
            CraftResult r = new CraftingPlanner().withWallet(Map.of(NOTE, c[0]))
                    .evaluateAll(List.of(parent, inter), stock, Map.of(), tp, OWN_ONLY, Set.of(1, 2)).get(1);
            assertEquals(c[1], r.craftableCount, "wallet " + c[0]);
            assertEquals(c[1] * 90, r.currencyTotal.getOrDefault(NOTE, 0), "wallet " + c[0]);
        }
    }

    @Test
    void currencySpentOnlyByLaterCraftsOfANestedRecipeIsStillConsumedAndReported() {
        // Parent needs 2 intermediates and has no currency itself; the intermediate costs 30 notes.
        // Two intermediates are owned: the first parent craft spends 0 notes, the second crafts 2 -> 60.
        Recipe inter = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Recipe parent = new Recipe(1, 100, 1, 0, "Artificer", List.of(ingredient(200, 2)));
        Map<Integer, Integer> stock = Map.of(200, 2, 300, 100);
        Map<Integer, PriceQuote> tp = Map.of(100, quote(5000, 6000));

        CraftResult enough = new CraftingPlanner().withWallet(Map.of(NOTE, 60))
                .evaluateAll(List.of(parent, inter), stock, Map.of(), tp, OWN_ONLY, Set.of(1, 2)).get(1);
        assertEquals(2, enough.craftableCount);
        assertEquals(60, enough.currencyTotal.getOrDefault(NOTE, 0));
        assertEquals("KNOWN", enough.walletState);

        CraftResult short59 = new CraftingPlanner().withWallet(Map.of(NOTE, 59))
                .evaluateAll(List.of(parent, inter), stock, Map.of(), tp, OWN_ONLY, Set.of(1, 2)).get(1);
        assertEquals(1, short59.craftableCount, "the accepted prefix is kept; the failed second craft restores the pool");
        assertEquals(0, short59.currencyTotal.getOrDefault(NOTE, 0));
    }

    @Test
    void itemOnlyRecipesNeedNoWalletAndReportNoCurrency() {
        Recipe plain = new Recipe(5, 500, 1, 0, "Artificer", List.of(ingredient(600, 1)));
        CraftResult r = new CraftingPlanner().withWallet(null)
                .evaluateAll(List.of(plain), Map.of(600, 3), Map.of(), Map.of(500, quote(10, 20)), OWN_ONLY, Set.of(5)).get(5);
        assertEquals(3, r.craftableCount);
        assertTrue(r.currencyPerCraft.isEmpty());
        assertEquals("NOT_REQUIRED", r.walletState);
    }
}
