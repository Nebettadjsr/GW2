package craft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Each Forge menu policy against an independent fixture (backend authority, no browser arithmetic). */
class ForgeSettingsPlannerTest {
    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000, DAILY = 43772, UNPRICED = 9999;
    private static final int OBSIDIAN = 19925, COIN = 19976, ECTO = 19721, STONE = 20796, CLOVER = 19675;

    private static Recipe forge(int id, int out, List<Ingredient> in) {
        return new Recipe(-id, out, 1, 0, "", in, List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private static ForgeSettings with(ForgeSettings.PriceMode pm, boolean use, boolean value, ForgeSettings.DailyMode d,
                                      boolean promo, ForgeSettings.ReduceMode r) {
        return new ForgeSettings(pm, use, value, d, promo, r);
    }

    private static ForgeProjectPlanner.ItemLine line(ForgeProjectPlanner.Project p, int id) {
        return p.items().stream().filter(i -> i.itemId() == id).findFirst().orElseThrow();
    }

    private static ForgeProjectPlanner.Project run(Recipe root, List<Recipe> producers, ForgeProjectPlanner.Inventory inv,
                                                   Map<Integer, PriceQuote> prices, ForgeSettings s) {
        List<Recipe> all = new ArrayList<>(producers);
        all.add(root);
        return new ForgeProjectPlanner(all).plan(root, 1, false, inv, ForgeProjectPlanner.Context.NONE, null, prices, s);
    }

    private static ForgeProjectPlanner.Inventory sellable(Map<Integer, Integer> m) {
        return new ForgeProjectPlanner.Inventory(m, Map.of(), Map.of());
    }

    private final Recipe root = forge(1, WEAPON, List.of(new Ingredient(ORE, 10), new Ingredient(UNPRICED, 1)));

    @Test
    void instantBuyUsesAskBuyOrderUsesBidAndMissingQuoteIsUnknown() {
        var prices = Map.of(ORE, new PriceQuote(3, 5));
        var ask = run(root, List.of(), sellable(Map.of()), prices, ForgeSettings.DEFAULT);
        var bid = run(root, List.of(), sellable(Map.of()), prices,
                with(ForgeSettings.PriceMode.BUY_ORDER, true, true, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertEquals(50, ask.knownCopper());
        assertEquals(30, bid.knownCopper());
        assertEquals(List.of(UNPRICED), bid.unpricedItemIds(), "a missing quote is unknown, never free");
        var noBid = run(root, List.of(), sellable(Map.of()), Map.of(ORE, new PriceQuote(null, 5)),
                with(ForgeSettings.PriceMode.BUY_ORDER, true, true, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertTrue(noBid.unpricedItemIds().contains(ORE));
        assertEquals(0, noBid.knownCopper());
    }

    @Test
    void useOwnChangesPhysicalConsumptionValueOwnOnlyChangesTheValuation() {
        var prices = Map.of(ORE, new PriceQuote(3, 5));
        var inv = sellable(Map.of(ORE, 4, UNPRICED, 1));
        var useYes = run(root, List.of(), inv, prices, ForgeSettings.DEFAULT);
        assertEquals(4, line(useYes, ORE).fromOwned());
        assertEquals(20, useYes.ownValue().knownCopper(), "4 owned Ore at the selected ask");
        assertEquals(List.of(UNPRICED), useYes.ownValue().unpricedItemIds(), "unpriced owned stays unknown, never free");
        var valueNo = run(root, List.of(), inv, prices,
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertNull(valueNo.ownValue());
        assertEquals(4, line(valueNo, ORE).fromOwned(), "valuation never changes consumed quantities");
        assertEquals(useYes.knownCopper(), valueNo.knownCopper());
        var useNo = run(root, List.of(), inv, prices,
                with(ForgeSettings.PriceMode.INSTANT_BUY, false, true, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertEquals(0, line(useNo, ORE).fromOwned());
        assertEquals(10, line(useNo, ORE).missing());
        assertEquals(0, useNo.ownValue().knownCopper());
    }

    @Test
    void dailyOutputCraftsOrIsBoughtAtTheSelectedPrice() {
        Recipe top = forge(2, WEAPON, List.of(new Ingredient(DAILY, 2)));
        Recipe dailyRecipe = new Recipe(50, DAILY, 1, 400, "Weaponsmith", List.of(new Ingredient(ORE, 3)));
        var prices = Map.of(DAILY, new PriceQuote(7, 9));
        var craft = run(top, List.of(dailyRecipe), sellable(Map.of(DAILY, 1)), prices, ForgeSettings.DEFAULT);
        assertEquals(1, craft.steps().stream().filter(s -> s.recipeId() == 50).findFirst().orElseThrow().crafts());
        assertEquals(0, line(craft, DAILY).missing());
        var buy = run(top, List.of(dailyRecipe), sellable(Map.of(DAILY, 1)), prices,
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, true, ForgeSettings.DailyMode.BUY, true, ForgeSettings.ReduceMode.YES));
        assertEquals(1, line(buy, DAILY).fromOwned(), "Buy still uses allowed owned stock");
        assertEquals(1, line(buy, DAILY).missing(), "the shortfall is purchased, not crafted");
        assertTrue(buy.steps().stream().noneMatch(s -> s.recipeId() == 50));
        assertEquals(9, buy.knownCopper());
    }

    @Test
    void disabledPromotionsLeaveTheMaterialShortfallToAcquire() {
        Recipe promo = forge(3, PLATE, List.of(new Ingredient(ORE, 2)));
        Recipe top = forge(4, WEAPON, List.of(new Ingredient(PLATE, 1)));
        assertTrue(run(top, List.of(promo), sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT).steps().stream()
                .anyMatch(s -> s.recipeId() == promo.recipeId));
        var off = run(top, List.of(promo), sellable(Map.of()), Map.of(),
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, true, ForgeSettings.DailyMode.CRAFT, false, ForgeSettings.ReduceMode.YES));
        assertEquals(1, line(off, PLATE).missing());
        assertTrue(off.steps().stream().noneMatch(s -> s.recipeId() == promo.recipeId));
        assertEquals(top.recipeId, off.recipeId(), "the selected root itself is still planned");
    }

    @Test
    void rootReductionRespectsSellableAndBoundPools() {
        var inv = new ForgeProjectPlanner.Inventory(Map.of(WEAPON, 1), Map.of(WEAPON, 2), Map.of());
        Recipe top = forge(5, WEAPON, List.of(new Ingredient(ORE, 1)));
        var planner = new ForgeProjectPlanner(List.of(top));
        long[] expectedOwned = {0, 3, 1, 2};
        var modes = ForgeSettings.ReduceMode.values();
        for (int i = 0; i < modes.length; i++) {
            var p = planner.plan(top, 5, false, inv, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(),
                    with(ForgeSettings.PriceMode.INSTANT_BUY, true, true, ForgeSettings.DailyMode.CRAFT, true, modes[i]));
            assertEquals(expectedOwned[i], p.rootOwned(), modes[i].name());
            assertEquals(5 - expectedOwned[i], p.batches(), modes[i].name());
        }
    }

    @Test
    void cloverEstimateRoundsAggregateAttemptsUpOnceAndChargesSharedInputsOnce() {
        Recipe top = forge(6, WEAPON, List.of(new Ingredient(CLOVER, 18), new Ingredient(OBSIDIAN, 5)));
        List<Recipe> producers = new ArrayList<>(VendorAcquisitions.forgeProjectRecipes());
        var p = run(top, producers, sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        assertEquals(59, p.clover().attempts());
        assertEquals(18, p.clover().missing());
        assertEquals(0, line(p, CLOVER).missing(), "expected Clovers are an estimate, never a shopping/owned fact");
        assertEquals(59 + 5, line(p, OBSIDIAN).needed(), "direct and estimated Obsidian are charged once together");
        assertEquals(59, line(p, COIN).needed());
        assertEquals(59, line(p, ECTO).needed());
        assertEquals(354, line(p, STONE).needed());
        var stones = p.vendorPurchases().stream().filter(v -> v.itemId() == STONE).findFirst().orElseThrow();
        assertEquals(36, stones.purchases());
        assertEquals(360, stones.units());
        var obsidian = p.vendorPurchases().stream().filter(v -> v.itemId() == OBSIDIAN).findFirst().orElseThrow();
        assertEquals(64, obsidian.purchases());
        var karma = p.currencies().stream().filter(c -> c.currencyId() == 2).findFirst().orElseThrow();
        assertEquals(64L * 2100, karma.needed());
        assertNull(karma.owned(), "an unknown wallet stays unknown");
        var shards = p.currencies().stream().filter(c -> c.currencyId() == 23).findFirst().orElseThrow();
        assertEquals(36, shards.needed());
        assertEquals(36 * 1, p.vendorPurchases().stream().filter(v -> v.itemId() == STONE)
                .flatMap(v -> v.currencies().stream()).mapToLong(ForgeProjectPlanner.CurrencyLine::needed).sum());
    }

    @Test
    void ownedCloverInputsReduceShoppingAndOwnedCloversReduceAttempts() {
        Recipe top = forge(7, WEAPON, List.of(new Ingredient(CLOVER, 18)));
        List<Recipe> producers = new ArrayList<>(VendorAcquisitions.forgeProjectRecipes());
        var p = run(top, producers, sellable(Map.of(CLOVER, 8, COIN, 9)), Map.of(), ForgeSettings.DEFAULT);
        assertEquals(10, p.clover().missing());
        assertEquals(33, p.clover().attempts(), "ceil(10 / 0.31)");
        assertEquals(9, line(p, COIN).fromOwned());
        assertEquals(24, line(p, COIN).missing());
    }

    @Test
    void bloodstoneIsAVendorCurrencyRequirementNotABareLeaf() {
        Recipe top = forge(8, WEAPON, List.of(new Ingredient(VendorAcquisitions.BLOODSTONE_SHARD, 2)));
        var p = run(top, new ArrayList<>(VendorAcquisitions.forgeProjectRecipes()), sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        assertEquals(400, p.currencies().stream().filter(c -> c.currencyId() == 23).findFirst().orElseThrow().needed());
        assertEquals(0, line(p, VendorAcquisitions.BLOODSTONE_SHARD).missing());
        assertNotNull(VendorAcquisitions.merchantStep(VendorAcquisitions.BLOODSTONE_SHARD));
    }

    @Test
    void ordinaryVendorFactsAreUnchangedForProfitAndDiscovery() {
        assertTrue(VendorAcquisitions.recipes().stream().noneMatch(r -> r.outputItemId == OBSIDIAN || r.outputItemId == 20797));
    }
}
