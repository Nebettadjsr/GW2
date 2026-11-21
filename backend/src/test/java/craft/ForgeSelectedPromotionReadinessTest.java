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

    // --- scheduled Research Note production inside a nested consumer (issue 378) ---
    private static final int LOGS = 19722, LOWER = 500, NDUST = 501, STONES = 20796, PRR_ITEM = 104934, CONSUMER = 4001, NROOT = 4000;
    private final Recipe logsPromo = Recipe.forgeWithExpectedYield(-32, LOGS, ExpectedYield.of("86.34", 40, 200),
            List.of(new Ingredient(LOWER, 250), new Ingredient(LOGS, 1), new Ingredient(NDUST, 5), new Ingredient(STONES, 1)));
    private final Recipe prr = new Recipe(15131, PRR_ITEM, 1, 0, "Artificer", List.of(new Ingredient(LOGS, 30)));
    private final Recipe consumer = new Recipe(98765, CONSUMER, 1, 0, "Artificer", List.of(),
            List.of(new CurrencyRequirement(61, 30)), RecipeSource.ORDINARY);
    private final Recipe noteRoot = new Recipe(-21, NROOT, 1, 0, "",
            List.of(new Ingredient(CONSUMER, 1), new Ingredient(LOGS, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
    private final ForgeProjectPlanner.Context ann = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15131, 98765));
    private final Map<Integer, PriceQuote> notePrices = Map.of(LOGS, new PriceQuote(100, 100), LOWER, new PriceQuote(1, 2),
            NDUST, new PriceQuote(1, 2), STONES, new PriceQuote(1, 2));

    private Result noteRun(Recipe forgeRoot, Recipe[] consumers, Map<Integer, Integer> owned) {
        var settings = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.NO);
        var all = new java.util.ArrayList<>(List.of(logsPromo, prr, consumer, forgeRoot));
        all.addAll(List.of(consumers));
        var planner = new ForgeProjectPlanner(all, settings);
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var project = planner.plan(forgeRoot, 1, false, inv, ann, Map.of(61, 0L), notePrices, settings);
        return new Result(project, planner.materialProgress(forgeRoot, 1, inv, ann, Map.of(61, 0L), project).percent());
    }

    private Result noteRun(Map<Integer, Integer> owned) { return noteRun(noteRoot, new Recipe[0], owned); }

    @Test
    void nestedNoteProductionWithEverythingOwnedIsFullyReady() {
        var r = noteRun(Map.of(LOGS, 2, LOWER, 250, NDUST, 5, STONES, 1));
        assertTrue(r.project().complete());
        assertTrue(r.project().items().stream().allMatch(i -> i.missing() == 0), "no shopping: " + r.project().items());
        assertEquals(100.0, r.percent());
    }

    @Test
    void nestedNoteProductionWithOneMissingDustIsPartialUnderEqualShares() {
        var r = noteRun(Map.of(LOGS, 2, LOWER, 250, NDUST, 4, STONES, 1));
        assertEquals(97.5, r.percent(), "promotion (1+1+0.8+1)/4 = .95; root (.95+1)/2");
    }

    @Test
    void rootKeepsItsLogAndTheNotePromotionAcquiresItsExternalSeed() {
        var r = noteRun(Map.of(LOGS, 1, LOWER, 250, NDUST, 5, STONES, 1));
        assertEquals(1, missing(r.project(), LOGS), "the promotion seed is the only purchase");
        assertEquals(87.5, r.percent(), "Note path .75 (seed missing), root Log 1");
    }

    @Test
    void twoNestedNoteConsumersShareOneScheduledProduction() {
        Recipe second = new Recipe(98766, 4002, 1, 0, "Artificer", List.of(), List.of(new CurrencyRequirement(61, 30)), RecipeSource.ORDINARY);
        Recipe twoRoot = new Recipe(-22, 4003, 1, 0, "",
                List.of(new Ingredient(CONSUMER, 1), new Ingredient(4002, 1), new Ingredient(LOGS, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        var r = noteRun(twoRoot, new Recipe[]{second}, Map.of(LOGS, 2, LOWER, 250, NDUST, 5, STONES, 1));
        assertEquals(1, r.project().steps().stream().filter(s -> s.recipeId() == 15131).findFirst().orElseThrow().crafts(),
                "60 Notes need one 150-Note container");
        assertTrue(r.project().items().stream().allMatch(i -> i.missing() == 0), "no double seed/material debit");
        assertEquals(100.0, r.percent());
        var partial = noteRun(twoRoot, new Recipe[]{second}, Map.of(LOGS, 2, LOWER, 250, NDUST, 4, STONES, 1));
        assertEquals(96.6, partial.percent(), "(0.95 + 0.95 + 1) / 3 = 96.67 floored, one shared production");
    }

    @Test
    void partiallyReadyPromotionSurplusIsConservedAtTheBatchCoverage() {
        final int out = 2, ore = 3, helperItem = 4;
        Recipe promotion = Recipe.forgeWithExpectedYield(-2, out, ExpectedYield.of("10", 5, 15),
                List.of(new Ingredient(ore, 2), new Ingredient(out, 1)));
        Recipe helper = new Recipe(44, helperItem, 1, 0, "Artificer", List.of(new Ingredient(out, 1)));
        Recipe forgeRoot = new Recipe(-1, 1, 1, 0, "", List.of(new Ingredient(out, 2), new Ingredient(helperItem, 1)),
                List.of(), RecipeSource.MYSTIC_FORGE);
        var ctx = new ForgeProjectPlanner.Context(List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(44));
        var settings = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false, ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.NO);
        var planner = new ForgeProjectPlanner(List.of(promotion, helper, forgeRoot), settings);
        var inv = new ForgeProjectPlanner.Inventory(Map.of(ore, 2), Map.of(), Map.of());
        var prices = Map.of(out, new PriceQuote(100, 100), ore, new PriceQuote(1, 1));
        var project = planner.plan(forgeRoot, 1, false, inv, ctx, Map.of(), prices, settings);

        assertTrue(project.complete());
        var seed = project.items().stream().filter(i -> i.itemId() == out).findFirst().orElseThrow();
        assertEquals(1, seed.missing(), "one external seed is bought");
        assertEquals(List.of(out, helperItem, 1), project.steps().stream().map(ForgeProjectPlanner.CraftStep::outputItemId).toList());
        assertTrue(project.steps().stream().allMatch(s -> s.crafts() == 1));
        // Seed 0% + Ore 100% => batch 50%; the batch's surplus (initial 2 + later 1) is conserved at that 50%,
        // so both root branches are 50% and overall readiness is 50.0 (not 25.0 from a discarded surplus).
        assertEquals(50.0, planner.materialProgress(forgeRoot, 1, inv, ctx, Map.of(), project).percent());
    }

    private static void collect(ForgeProjectPlanner.Node node, int itemId, List<ForgeProjectPlanner.Node> out) {
        if (node.itemId() == itemId) out.add(node);
        if (node.itemId() != itemId && node.children() != null) node.children().forEach(c -> collect(c, itemId, out));
    }
}
