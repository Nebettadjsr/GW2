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
        assertEquals(12, useYes.ownValue().knownCopper(), "4 owned sellable Ore at the instant-sell bid 3, not the ask 5");
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
    void disabledPromotionsDropOnlyOptionalPromotionsAndKeepRequiredGifts() {
        Recipe promo = Recipe.forgeWithExpectedYield(-3, PLATE, ExpectedYield.of("3.5", 2, 6),
                List.of(new Ingredient(PLATE, 1), new Ingredient(ORE, 2)));
        int gift1 = 7001, gift2 = 7002;
        Recipe fortune = forge(11, gift1, List.of(new Ingredient(ORE, 3)));
        Recipe mastery = forge(12, gift2, List.of(new Ingredient(gift1, 1), new Ingredient(ORE, 4)));
        Recipe top = forge(4, WEAPON, List.of(new Ingredient(PLATE, 1), new Ingredient(gift2, 1)));
        var producers = List.of(promo, fortune, mastery);
        var on = run(top, producers, sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        // Allow: the verified promotion supplies the Plate with its guaranteed minimum (2, seed 1 external).
        assertTrue(on.steps().stream().anyMatch(s -> s.recipeId() == promo.recipeId));
        assertEquals(0, line(on, PLATE).missing() - 1, "only the self-seed is acquired externally");
        assertTrue(on.steps().stream().anyMatch(s -> s.recipeId() == mastery.recipeId));
        var off = run(top, producers, sellable(Map.of()), Map.of(),
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, true, ForgeSettings.DailyMode.CRAFT, false, ForgeSettings.ReduceMode.YES));
        assertEquals(1, line(off, PLATE).missing(), "the optional promotion is left to acquire");
        assertTrue(off.steps().stream().noneMatch(s -> s.recipeId() == promo.recipeId));
        assertTrue(off.steps().stream().anyMatch(s -> s.recipeId() == fortune.recipeId), "required fixed gift stays");
        assertTrue(off.steps().stream().anyMatch(s -> s.recipeId() == mastery.recipeId), "required fixed gift stays");
        assertEquals(0, line(off, gift1).missing());
        assertEquals(0, line(off, gift2).missing());
        assertEquals(top.recipeId, off.recipeId());
    }

    @Test
    void separateOneCloverDemandsChargeAggregateAttemptsAndNeverGuaranteeOutput() {
        Recipe top = forge(9, WEAPON, List.of(new Ingredient(CLOVER, 1), new Ingredient(CLOVER, 1)));
        List<Recipe> producers = new ArrayList<>(VendorAcquisitions.forgeProjectRecipes());
        var p = run(top, producers, sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        assertEquals(7, p.clover().attempts(), "ceil(2/0.31), not 4+4");
        assertEquals(7, line(p, COIN).needed(), "inputs charged once for the aggregate");
        var nodes = p.tree().children().stream().filter(n -> n.itemId() == CLOVER).toList();
        assertEquals(List.of(4L, 3L), nodes.stream().map(ForgeProjectPlanner.Node::crafts).toList());
        assertEquals(-1, p.guaranteedOutput(), "an estimated Clover dependency never guarantees output");
        var plain = run(forge(10, WEAPON, List.of(new Ingredient(ORE, 1))), List.of(), sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        assertEquals(1, plain.guaranteedOutput());
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

    @Test
    void promotionAllowUsesGuaranteedNetOutputWithSelfSeedReuseAndOffLeavesAcquisition() {
        int scrap = 19745, inputA = 19748;
        Recipe gossamer = Recipe.forgeWithExpectedYield(-50, scrap, ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(inputA, 250), new Ingredient(scrap, 1)));
        Recipe top = forge(60, WEAPON, List.of(new Ingredient(scrap, 20)));
        var on = run(top, List.of(gossamer), sellable(Map.of()), Map.of(), ForgeSettings.DEFAULT);
        var step = on.steps().stream().filter(s -> s.recipeId() == gossamer.recipeId).findFirst().orElseThrow();
        assertEquals(3, step.crafts(), "min10/seed1 needs 3 batches (net 28), not 2 (net 19)");
        assertEquals(750, line(on, inputA).needed());
        assertEquals(1, line(on, scrap).missing(), "one external seed only");
        assertEquals(10, ForgeProjectPlanner.promotionNet(gossamer, 1));
        assertEquals(19, ForgeProjectPlanner.promotionNet(gossamer, 2));
        assertEquals(28, ForgeProjectPlanner.promotionNet(gossamer, 3));
        assertEquals(8, on.tree().children().get(0).newSurplus(), "net 28 - 20 demanded");
        Recipe t6 = Recipe.forgeWithExpectedYield(-51, 5001, ExpectedYield.of("5.5", 5, 8),
                List.of(new Ingredient(5001, 1), new Ingredient(ORE, 1)));
        assertEquals(201, ForgeProjectPlanner.promotionNet(t6, 50));
        assertEquals(50, ForgeProjectPlanner.promotionBatches(t6, 201));
        var off = run(top, List.of(gossamer), sellable(Map.of()), Map.of(),
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, true, ForgeSettings.DailyMode.CRAFT, false, ForgeSettings.ReduceMode.YES));
        assertEquals(20, line(off, scrap).missing());
        assertTrue(off.steps().stream().noneMatch(s -> s.recipeId() == gossamer.recipeId));
    }

    @Test
    void researchNotesRemainingAcquisitionUsesUncoveredDemandAndProductionPrecedesConsumer() {
        int note = CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID;
        Recipe consumer = new Recipe(-70, WEAPON, 1, 0, "", List.of(new Ingredient(ORE, 1)),
                List.of(new CurrencyRequirement(note, 30)), RecipeSource.MYSTIC_FORGE);
        Recipe prr = new Recipe(15120, CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID, 1, 0, "Artificer",
                List.of(new Ingredient(ORE + 1, 4)));
        var planner = new ForgeProjectPlanner(List.of(consumer, prr));
        var covered = planner.plan(consumer, 1, true, sellable(Map.of()), ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertEquals(30, covered.notes().shortfall(), "before-production deficit stays available");
        assertEquals(0, covered.notes().uncoveredNotes());
        var cur = covered.currencies().get(0);
        assertEquals(30, cur.needed());
        assertEquals(0, cur.missing(), "one planned 150-Note container leaves nothing to acquire");
        assertEquals(prr.recipeId, covered.steps().get(0).recipeId(), "production precedes the consumer craft");
        assertEquals(consumer.recipeId, covered.steps().get(covered.steps().size() - 1).recipeId());
        assertEquals(prr.recipeId, covered.tree().children().get(0).recipeId(), "tree order is executable");
        assertEquals(4, line(covered, ORE + 1).needed(), "container inputs counted once");
        // Genuinely uncovered: no producer available, so the whole deficit remains to acquire.
        var none = new ForgeProjectPlanner(List.of(consumer)).plan(consumer, 1, true, sellable(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
        assertEquals(30, none.currencies().get(0).missing());
        // Unknown wallet: nothing claimed.
        var unknown = planner.plan(consumer, 1, true, sellable(Map.of()), ForgeProjectPlanner.Context.NONE, null, Map.of());
        assertNull(unknown.currencies().get(0).missing());
    }

    private static ForgeProjectPlanner.Inventory pools(Map<Integer, Integer> sell, Map<Integer, Integer> bound) {
        return new ForgeProjectPlanner.Inventory(sell, bound, Map.of());
    }

    private static final int BOUND = 7001, PART = 7002, VPART = 7003;

    private ForgeProjectPlanner.Project ownRun(Recipe r, List<Recipe> prod, ForgeProjectPlanner.Inventory inv,
                                               Map<Integer, PriceQuote> prices, ForgeProjectPlanner.Context ctx, ForgeSettings s) {
        List<Recipe> all = new ArrayList<>(prod);
        all.add(r);
        return new ForgeProjectPlanner(all).plan(r, 1, false, inv, ctx, null, prices, s);
    }

    @Test
    void ownValueUsesBidForSellableAndCraftingCostForBoundCopiesOfTheSameItem() {
        Recipe r = forge(10, WEAPON, List.of(new Ingredient(BOUND, 3)));
        Recipe producer = new Recipe(70, BOUND, 1, 0, "", List.of(new Ingredient(PART, 2)), List.of(), RecipeSource.ORDINARY);
        Map<Integer, PriceQuote> prices = Map.of(BOUND, new PriceQuote(40, 90), PART, new PriceQuote(5, 7));
        // 1 sellable (bid 40) + 2 bound (crafting 2 parts x ask 7 = 14 each); the bound ones are NOT valued at the 40 quote.
        var p = ownRun(r, List.of(producer), pools(Map.of(BOUND, 1), Map.of(BOUND, 2)), prices,
                ForgeProjectPlanner.Context.NONE, ForgeSettings.DEFAULT);
        assertEquals(40 + 2 * 14, p.ownValue().knownCopper());
        assertTrue(p.ownValue().unpricedItemIds().isEmpty());
        assertEquals(3, line(p, BOUND).fromOwned());
        assertEquals(0, p.knownCopper(), "valuing owned stock never buys anything");
        // Value own = No: same physical use, no valuation.
        var no = ownRun(r, List.of(producer), pools(Map.of(BOUND, 1), Map.of(BOUND, 2)), prices, ForgeProjectPlanner.Context.NONE,
                with(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertNull(no.ownValue());
        assertEquals(3, line(no, BOUND).fromOwned());
    }

    @Test
    void ownValueIsIndependentOfTheAcquisitionPriceMode() {
        Recipe r = forge(11, WEAPON, List.of(new Ingredient(ORE, 2)));
        Map<Integer, PriceQuote> prices = Map.of(ORE, new PriceQuote(30, 50));
        var ask = ownRun(r, List.of(), sellable(Map.of(ORE, 2)), prices, ForgeProjectPlanner.Context.NONE, ForgeSettings.DEFAULT);
        var bid = ownRun(r, List.of(), sellable(Map.of(ORE, 2)), prices, ForgeProjectPlanner.Context.NONE,
                with(ForgeSettings.PriceMode.BUY_ORDER, true, true, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES));
        assertEquals(60, ask.ownValue().knownCopper());
        assertEquals(60, bid.ownValue().knownCopper());
    }

    @Test
    void unquotedSellableAndBoundStockFallBackToCraftingThenVendorValueAndUnpricedStaysExplicit() {
        Recipe r = forge(12, WEAPON, List.of(new Ingredient(VPART, 2), new Ingredient(UNPRICED, 1), new Ingredient(BOUND, 1)));
        Recipe cycA = new Recipe(71, BOUND, 1, 0, "", List.of(new Ingredient(PART, 1)), List.of(), RecipeSource.ORDINARY);
        Recipe cycB = new Recipe(72, PART, 1, 0, "", List.of(new Ingredient(BOUND, 1)), List.of(), RecipeSource.ORDINARY);
        // VPART: no quote, vendor value 33 via a valuer built the way the application captures vendor values.
        PlannerContext pc = new PlannerContext(Map.of(BOUND, List.of(cycA), PART, List.of(cycB)), Map.of(),
                new CraftingSettings(true, true, 0, false, false, false), null).withVendorValues(Map.of(VPART, 33));
        var ctx = new ForgeProjectPlanner.Context(List.of(), null, new CraftingResolver().ownedValuer(pc));
        var p = ownRun(r, List.of(), pools(Map.of(VPART, 1, UNPRICED, 1), Map.of(VPART, 1, BOUND, 1)), Map.of(), ctx, ForgeSettings.DEFAULT);
        assertEquals(66, p.ownValue().knownCopper(), "sellable + bound vendor-fallback copies, 33 each");
        assertEquals(List.of(BOUND, UNPRICED), p.ownValue().unpricedItemIds(), "cycle and no-value stay explicit, never free");
    }

    @Test
    void fractionalCraftingValueRoundsOnceOverTheAggregateConsumedQuantity() {
        Recipe r = forge(13, WEAPON, List.of(new Ingredient(BOUND, 3)));
        // 3 parts at 10c -> 2 outputs: 15c each; 3 bound copies = 45, aggregate (3 parts... exact), vs per-unit rounding.
        Recipe producer = new Recipe(73, BOUND, 2, 0, "", List.of(new Ingredient(PART, 1)), List.of(), RecipeSource.ORDINARY);
        Map<Integer, PriceQuote> prices = Map.of(PART, new PriceQuote(1, 5));
        var p = ownRun(r, List.of(producer), pools(Map.of(), Map.of(BOUND, 3)), prices, ForgeProjectPlanner.Context.NONE, ForgeSettings.DEFAULT);
        assertEquals(8, p.ownValue().knownCopper(), "ceil(3 x 5/2) = 8 once, not 3 x ceil(2.5) = 9");
    }

    @Test
    void ownValueAggregateBeyondIntButWithinTheExactRangeIsKept() {
        Recipe r = forge(14, WEAPON, List.of(new Ingredient(ORE, 2_000_000_000)));
        var p = ownRun(r, List.of(), sellable(Map.of(ORE, 2_000_000_000)), Map.of(ORE, new PriceQuote(2_000_000, 3_000_000)),
                ForgeProjectPlanner.Context.NONE, ForgeSettings.DEFAULT);
        assertTrue(p.complete());
        assertEquals(4_000_000_000_000_000L, p.ownValue().knownCopper());
    }

    @Test
    void ownValueBeyondTheExactRangeStopsWithNoGuaranteedOutputAndNoFullMargin() {
        Recipe r = forge(15, WEAPON, List.of(new Ingredient(ORE, 2_000_000_000)));
        var p = ownRun(r, List.of(), sellable(Map.of(ORE, 2_000_000_000)),
                Map.of(ORE, new PriceQuote(2_000_000_000, null), WEAPON, new PriceQuote(5, 6)),
                ForgeProjectPlanner.Context.NONE, ForgeSettings.DEFAULT);
        assertFalse(p.complete());
        assertEquals("own-material value exceeds the supported range", p.incompleteReason());
        assertEquals(-1, p.guaranteedOutput());
        assertNotNull(p.economics());
        assertNull(p.economics().marginCopper());
        assertNull(p.economics().marginOnShoppingCopper());
    }
}
