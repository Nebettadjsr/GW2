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
    void notesSpentWhileExploringAFailedAlternativeAreRestoredForTheFeasibleOne() {
        // Item 200 has two recipes, each costing 30 notes. The first needs item 400 (none owned, not buyable) and
        // fails after its note requirement was explored; the second is feasible. Wallet 30: the failed attempt must
        // give its notes back, so the alternate succeeds and exactly 30 are spent (not 60, not 0).
        Recipe failing = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(400, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Recipe feasible = new Recipe(3, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Recipe parent = new Recipe(1, 100, 1, 0, "Artificer", List.of(ingredient(200, 1)));
        Map<Integer, Integer> stock = Map.of(300, 5);
        Map<Integer, PriceQuote> tp = Map.of(100, quote(5000, 6000));
        for (List<Recipe> order : List.of(List.of(parent, failing, feasible), List.of(parent, feasible, failing))) {
            CraftResult r = new CraftingPlanner().withWallet(Map.of(NOTE, 30))
                    .evaluateAll(order, stock, Map.of(), tp, OWN_ONLY, Set.of(1, 2, 3)).get(1);
            assertEquals(1, r.craftableCount, "feasible alternate crafts after the failed attempt: " + order);
            assertEquals(30, r.currencyTotal.getOrDefault(NOTE, 0), "exact aggregate spending, no double spend");
            assertEquals("KNOWN", r.walletState);
        }
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

    // ---- Unpriced-currency routes never win a fully valued ranking (issue #372) ----

    private static final CraftingSettings BUYING = new CraftingSettings(true, true, 0, false, false, true);

    private static CraftResult run(List<Recipe> recipes, Map<Integer, Integer> stock, Map<Integer, PriceQuote> tp,
                                   CraftingSettings settings, Map<Integer, Integer> wallet) {
        Set<Integer> ids = new java.util.HashSet<>();
        recipes.forEach(r -> ids.add(r.recipeId));
        return new CraftingPlanner().withWallet(wallet).evaluateAll(recipes, stock, Map.of(), tp, settings, ids).get(1);
    }

    private static final Recipe PARENT = new Recipe(1, 100, 1, 0, "Artificer", List.of(ingredient(200, 1)));

    @Test
    void cheaperLookingCurrencyCraftDoesNotBeatAFullyPricedPurchase() {
        // 200 craft = 50c material + 30 notes (unpriced); buying 200 costs 100c. Buying is the proven route.
        Recipe viaNotes = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Map<Integer, PriceQuote> tp = Map.of(100, quote(900, 1000), 200, quote(100, 100), 300, quote(50, 50));
        for (List<Recipe> order : List.of(List.of(PARENT, viaNotes), List.of(viaNotes, PARENT))) {
            CraftResult r = run(order, Map.of(), tp, BUYING, Map.of(NOTE, 300));
            assertTrue(r.craftableCount >= 1);
            assertEquals(Map.of(200, 1), r.missingToBuyOne, "bought, not crafted with notes");
            assertTrue(r.currencyTotal.isEmpty(), "no Research Notes spent: " + r.currencyTotal);
        }
    }

    @Test
    void fullyPricedCraftRouteBeatsCheaperLookingCurrencyRouteWhenBuyingIsOff() {
        Recipe viaNotes = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Recipe priced = new Recipe(3, 200, 1, 0, "Artificer", List.of(ingredient(400, 1)));
        Map<Integer, PriceQuote> tp = Map.of(100, quote(900, 1000), 300, quote(50, 50), 400, quote(80, 80));
        CraftingSettings craftOnly = new CraftingSettings(true, false, 0, false, false, true);
        // Material 300/400 are bought? buying is off, so own them; the opportunity cost is what ranks the routes.
        Map<Integer, Integer> stock = Map.of(300, 5, 400, 1000); // ample fully valued supply so no craft needs the notes route
        for (List<Recipe> order : List.of(List.of(PARENT, viaNotes, priced), List.of(PARENT, priced, viaNotes))) {
            CraftResult r = run(order, stock, tp, craftOnly, Map.of(NOTE, 300));
            assertTrue(r.craftableCount >= 1);
            assertTrue(r.currencyTotal.isEmpty(), "fully valued route chosen in " + order + ": " + r.currencyTotal);
        }
    }

    @Test
    void nestedCurrencyCostMakesTheWholeRouteUnpriced() {
        Recipe viaNotes = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(250, 1)));
        Recipe inner = new Recipe(4, 250, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        Map<Integer, PriceQuote> tp = Map.of(100, quote(900, 1000), 200, quote(100, 100), 300, quote(10, 10));
        CraftResult r = run(List.of(PARENT, viaNotes, inner), Map.of(), tp, BUYING, Map.of(NOTE, 300));
        assertEquals(Map.of(200, 1), r.missingToBuyOne);
        assertTrue(r.currencyTotal.isEmpty());
    }

    @Test
    void currencyOnlyFeasibleRouteStaysAvailableAsConditional() {
        Recipe viaNotes = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
                List.of(new CurrencyRequirement(NOTE, 30)));
        // 200 has no TP quote: the currency craft is the only way, exact notes are still spent and reported.
        Map<Integer, PriceQuote> tp = Map.of(100, quote(900, 1000), 300, quote(50, 50));
        CraftResult r = run(List.of(PARENT, viaNotes), Map.of(), tp, BUYING, Map.of(NOTE, 60));
        assertEquals(2, r.craftableCount, "wallet 60 funds exactly two note crafts");
        assertEquals(Map.of(NOTE, 60), r.currencyTotal);
        assertEquals(Map.of(NOTE, 30), r.currencyPerCraft);
        // And a currency route that cannot be afforded still blocks honestly.
        CraftResult poor = run(List.of(PARENT, viaNotes), Map.of(), tp, BUYING, Map.of(NOTE, 10));
        assertEquals(0, poor.craftableCount);
    }

    @Test
    void itemOnlyChoiceIsUnchanged() {
        Recipe cheapCraft = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)));
        Map<Integer, PriceQuote> tp = Map.of(100, quote(900, 1000), 200, quote(100, 100), 300, quote(50, 50));
        CraftResult r = run(List.of(PARENT, cheapCraft), Map.of(), tp, BUYING, null);
        assertEquals(Map.of(300, 1), r.missingToBuyOne, "cheaper item-only craft still beats the 100c purchase");
        assertTrue(r.currencyTotal.isEmpty());
    }
}
