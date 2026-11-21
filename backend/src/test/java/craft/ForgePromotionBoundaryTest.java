package craft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Optional promotion decision boundaries, hand-computed from the fixture prices (promotions setting ON). */
class ForgePromotionBoundaryTest {
    private static final int WEAPON = 1000, PLATE = 2000, ORE = 3000, DUST = 3001;

    private static Recipe forge(int id, int out, List<Ingredient> in) {
        return new Recipe(-id, out, 1, 0, "", in, List.of(), RecipeSource.MYSTIC_FORGE);
    }

    // one batch: seed Plate x1 + Ore x2 -> 2..6 Plate (mean 3.5)
    private final Recipe promo = Recipe.forgeWithExpectedYield(-3, PLATE, ExpectedYield.of("3.5", 2, 6),
            List.of(new Ingredient(PLATE, 1), new Ingredient(ORE, 2)));

    private ForgeProjectPlanner.Project run(long need, List<Recipe> extra, Map<Integer, Integer> owned, Map<Integer, PriceQuote> prices) {
        Recipe top = forge(1, WEAPON, List.of(new Ingredient(PLATE, (int) need)));
        List<Recipe> all = new ArrayList<>(extra);
        all.add(promo);
        all.add(top);
        return LegacyForge.planner(all).plan(top, 1, false, new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of()),
                ForgeProjectPlanner.Context.NONE, null, prices, LegacyForge.SETTINGS);
    }

    private static long promoSteps(ForgeProjectPlanner.Project p, int recipeId) {
        return p.steps().stream().filter(s -> s.recipeId() == recipeId).count();
    }

    private static long missing(ForgeProjectPlanner.Project p, int id) {
        return p.items().stream().filter(i -> i.itemId() == id).findFirst().orElseThrow().missing();
    }

    @Test
    void positiveEconomicsAndCheaperGuaranteedBatchesPromote() {
        // Plate ask 100/bid 100, Ore ask 2: buying 10 = 1000c; 9 batches (net 1 each +1 seed) cost far less
        var p = run(10, List.of(), Map.of(), Map.of(PLATE, new PriceQuote(100, 100), ORE, new PriceQuote(1, 2)));
        assertEquals(1, promoSteps(p, promo.recipeId));
        assertEquals(1, missing(p, PLATE), "only the external self-seed is bought");
    }

    @Test
    void unprofitableUnknownAndExpensiveForSmallShortfallStayPurchases() {
        // unprofitable: Plate sells for 1 -> expected gross 3.5c per batch cannot cover 4c of Ore plus seed
        var loss = run(10, List.of(), Map.of(), Map.of(PLATE, new PriceQuote(1, 100), ORE, new PriceQuote(1, 2)));
        assertEquals(0, promoSteps(loss, promo.recipeId));
        assertEquals(10, missing(loss, PLATE));
        // unknown: Ore has no quote, so the batch cost is not claimable
        var unknown = run(10, List.of(), Map.of(), Map.of(PLATE, new PriceQuote(100, 100)));
        assertEquals(0, promoSteps(unknown, promo.recipeId));
        assertEquals(10, missing(unknown, PLATE));
        // positive economics but dearer than buying 1 Plate: batch = seed 10 + 2 x Ore 5 = 20c > 10c
        var small = run(1, List.of(), Map.of(), Map.of(PLATE, new PriceQuote(10, 10), ORE, new PriceQuote(5, 5)));
        assertEquals(0, promoSteps(small, promo.recipeId), "one batch costs 20c, buying the single missing Plate costs 10c");
        assertEquals(1, missing(small, PLATE));
    }

    @Test
    void promotionInputsAreNeverPromotedInAChain() {
        Recipe oreFromDust = Recipe.forgeWithExpectedYield(-4, ORE, ExpectedYield.of("5.5", 5, 8),
                List.of(new Ingredient(ORE, 1), new Ingredient(DUST, 1)));
        var p = run(10, List.of(oreFromDust), Map.of(), Map.of(PLATE, new PriceQuote(100, 100), ORE, new PriceQuote(1, 2),
                DUST, new PriceQuote(1, 1)));
        assertEquals(1, promoSteps(p, promo.recipeId));
        assertEquals(0, promoSteps(p, oreFromDust.recipeId), "Ore stays a purchase; no T1-to-T6 chain");
        assertTrue(missing(p, ORE) > 0);
    }

    @Test
    void ownedOutputTierSeedIsReusedInsteadOfBuyingAnotherSeed() {
        var prices = Map.of(PLATE, new PriceQuote(100, 100), ORE, new PriceQuote(1, 2));
        var p = run(10, List.of(), Map.of(PLATE, 1), prices);
        assertEquals(1, promoSteps(p, promo.recipeId));
        assertEquals(0, missing(p, PLATE), "the owned Plate seeds the batches; nothing extra is bought");
    }
}
