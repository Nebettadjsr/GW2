package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overall readiness follows the promotion path the resolved project ACTUALLY selected, with guaranteed-minimum
 * quantities (never the expected mean). Promotion fixture: seed Plate x1 + Ore x2 -> 2..6 Plate (mean 3.5), so a
 * batch nets +1 Plate after reusing the minimum; 10 Plate = 9 batches = 18 Ore + one external seed.
 */
class ForgeSelectedPromotionReadinessTest {
    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000, DUST = 3001;
    private static final Map<Integer, PriceQuote> PRICES = Map.of(PLATE, new PriceQuote(100, 100), ORE, new PriceQuote(1, 2));

    private final Recipe promo = Recipe.forgeWithExpectedYield(-3, PLATE, ExpectedYield.of("3.5", 2, 6),
            List.of(new Ingredient(PLATE, 1), new Ingredient(ORE, 2)));

    private Recipe root(int plates) {
        return new Recipe(-1, WEAPON, 1, 0, "", List.of(new Ingredient(PLATE, plates)), List.of(), RecipeSource.MYSTIC_FORGE);
    }

    private record Result(ForgeProjectPlanner.Project project, double percent) { }

    private Result run(Recipe root, ForgeSettings settings, Map<Integer, Integer> owned, Map<Integer, PriceQuote> prices, List<Recipe> extra) {
        var all = new java.util.ArrayList<>(extra);
        all.add(promo);
        all.add(root);
        var planner = new ForgeProjectPlanner(all, settings);
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var project = planner.plan(root, 1, false, inv, ForgeProjectPlanner.Context.NONE, null, prices, settings);
        return new Result(project, planner.materialProgress(root, 1, inv, ForgeProjectPlanner.Context.NONE, null, project).percent());
    }

    private static long missing(ForgeProjectPlanner.Project p, int id) {
        return p.items().stream().filter(i -> i.itemId() == id).findFirst().map(ForgeProjectPlanner.ItemLine::missing).orElse(0L);
    }

    private static ForgeSettings promotions(boolean on) {
        return new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, on, ForgeSettings.ReduceMode.YES);
    }

    @Test
    void ownedSeedAndAllGuaranteedBatchInputsAreFullyReadyWithNoShopping() {
        var r = run(root(10), promotions(true), Map.of(PLATE, 1, ORE, 18), PRICES, List.of());
        assertEquals(9, r.project().steps().stream().filter(s -> s.recipeId() == promo.recipeId).findFirst().orElseThrow().crafts());
        assertEquals(0, missing(r.project(), PLATE), "the owned seed is the only external seed");
        assertEquals(0, missing(r.project(), ORE));
        assertEquals(100.0, r.percent());
    }

    @Test
    void missingOneInputUnitIsPartialNotReady() {
        var r = run(root(10), promotions(true), Map.of(PLATE, 1, ORE, 17), PRICES, List.of());
        assertEquals(1, missing(r.project(), ORE));
        assertTrue(r.percent() < 100.0 && r.percent() > 0.0, "partial: " + r.percent());
    }

    @Test
    void theSeedIsCountedOnceAndNeverAlsoCreditedAsFinishedOutput() {
        // Without the seed the same 18 Ore are not enough: a seed must still be acquired, so not ready.
        var r = run(root(10), promotions(true), Map.of(ORE, 18), PRICES, List.of());
        assertEquals(1, missing(r.project(), PLATE));
        assertTrue(r.percent() < 100.0);
        // Two owned Plate: one is the seed, the other is a finished unit -> 8 batches (16 Ore) cover the other 9.
        var two = run(root(10), promotions(true), Map.of(PLATE, 2, ORE, 16), PRICES, List.of());
        assertEquals(0, missing(two.project(), PLATE));
        assertEquals(0, missing(two.project(), ORE));
        assertEquals(100.0, two.percent());
        // ... and 17 Ore is one unit short of that guaranteed path.
        assertTrue(run(root(10), promotions(true), Map.of(PLATE, 2, ORE, 15), PRICES, List.of()).percent() < 100.0);
    }

    @Test
    void disabledPromotionsDoNotTreatLowerTierInputsAsReady() {
        var r = run(root(10), promotions(false), Map.of(PLATE, 1, ORE, 18), PRICES, List.of());
        assertEquals(9, missing(r.project(), PLATE));
        assertTrue(r.percent() < 100.0, "Plate is an acquisition leaf; 1 of 10 owned -> " + r.percent());
        assertEquals(10.0, r.percent());
    }

    @Test
    void unprofitableOrUnquotedPromotionStaysAnAcquisitionLeaf() {
        var cheapOutput = Map.of(PLATE, new PriceQuote(1, 100), ORE, new PriceQuote(1, 2));
        var loss = run(root(10), promotions(true), Map.of(PLATE, 1, ORE, 18), cheapOutput, List.of());
        assertEquals(0, loss.project().steps().stream().filter(s -> s.recipeId() == promo.recipeId).count());
        assertEquals(10.0, loss.percent());
        var unquoted = run(root(10), promotions(true), Map.of(PLATE, 1, ORE, 18), Map.of(PLATE, new PriceQuote(100, 100)), List.of());
        assertEquals(10.0, unquoted.percent());
    }

    @Test
    void promotionInputsAreNeverPromotedInAChainAndNoMeanGuarantee() {
        Recipe oreFromDust = Recipe.forgeWithExpectedYield(-4, ORE, ExpectedYield.of("5.5", 5, 8),
                List.of(new Ingredient(ORE, 1), new Ingredient(DUST, 1)));
        var prices = Map.of(PLATE, new PriceQuote(100, 100), ORE, new PriceQuote(1, 2), DUST, new PriceQuote(1, 1));
        // Owns Dust (a would-be T1 input of the Ore promotion) but no Ore: Ore stays an acquisition leaf.
        var chain = run(root(10), promotions(true), Map.of(PLATE, 1, DUST, 50), prices, List.of(oreFromDust));
        assertEquals(18, missing(chain.project(), ORE));
        assertTrue(chain.percent() < 100.0);
        // Expected-mean batches (10 Plate / 2.5 gain ~ 4) would need only 8 Ore; owning 8 must NOT read ready.
        var meanBased = run(root(10), promotions(true), Map.of(PLATE, 1, ORE, 8), PRICES, List.of());
        assertTrue(meanBased.percent() < 100.0, "readiness uses guaranteed minimum batches, not the mean");
    }

    @Test
    void sharedStockIsConservedAcrossABranchSiblingAndThePromotion() {
        // The root also needs 18 Ore directly: the same 18 Ore cannot satisfy both the promotion and the sibling.
        Recipe both = new Recipe(-1, WEAPON, 1, 0, "", List.of(new Ingredient(PLATE, 10), new Ingredient(ORE, 18)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        var r = run(both, promotions(true), Map.of(PLATE, 1, ORE, 18), PRICES, List.of());
        assertTrue(r.percent() < 100.0, "18 Ore owned cannot cover 36 Ore of demand: " + r.percent());
        var enough = run(both, promotions(true), Map.of(PLATE, 1, ORE, 36), PRICES, List.of());
        assertEquals(100.0, enough.percent());
    }

    @Test
    void promotionChoiceIsPerOccurrenceNotPerOutputItem() {
        final int plate = 2, ore = 3, x = 10, y = 11;
        Recipe promotion = Recipe.forgeWithExpectedYield(-2, plate, ExpectedYield.of("4", 2, 6),
                List.of(new Ingredient(ore, 2), new Ingredient(plate, 1)));
        Recipe needsTen = new Recipe(10, x, 1, 0, "Artificer", List.of(new Ingredient(plate, 10)));
        Recipe needsOne = new Recipe(11, y, 1, 0, "Artificer", List.of(new Ingredient(plate, 1)));
        Recipe forgeRoot = new Recipe(-1, 1, 1, 0, "", List.of(new Ingredient(x, 1), new Ingredient(y, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        var ctx = new ForgeProjectPlanner.Context(List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(10, 11));
        var settings = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.NO);
        var planner = new ForgeProjectPlanner(List.of(promotion, needsTen, needsOne, forgeRoot), settings);
        var inv = new ForgeProjectPlanner.Inventory(Map.of(plate, 2, ore, 18), Map.of(), Map.of());
        var prices = Map.of(plate, new PriceQuote(100, 100), ore, new PriceQuote(1, 2));
        var project = planner.plan(forgeRoot, 1, false, inv, ctx, Map.of(), prices, settings);

        var plateNodes = new java.util.ArrayList<ForgeProjectPlanner.Node>();
        collect(project.tree(), plate, plateNodes);
        assertEquals(2, plateNodes.size());
        var forX = plateNodes.get(0);
        var forY = plateNodes.get(1);
        assertEquals(10, forX.needed());
        assertEquals(1, forX.fromOwned());
        assertEquals(-2, forX.recipeId());
        assertEquals(8, forX.crafts());
        assertEquals(1, forY.needed());
        assertEquals(0, forY.fromOwned());
        assertEquals(1, forY.toAcquire());
        assertNull(forY.recipeId());

        double percent = planner.materialProgress(forgeRoot, 1, inv, ctx, Map.of(), project).percent();
        assertEquals(50.0, percent, "X's promoted path is fully supplied; Y's purchase leaf has no Plate");
    }

    private static void collect(ForgeProjectPlanner.Node node, int itemId, List<ForgeProjectPlanner.Node> out) {
        if (node.itemId() == itemId) out.add(node);
        if (node.itemId() != itemId && node.children() != null) node.children().forEach(c -> collect(c, itemId, out));
    }
}
