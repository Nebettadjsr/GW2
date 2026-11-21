package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred Research Notes are scheduled ONCE; a Note consumer batch's surplus is shared by later demand in readiness
 * exactly as the plan does (one batch of 100 serves the root's 1 and the helper's 50), at the producing batch's coverage.
 */
class ForgeDeferredNoteSurplusReadinessTest {
    private static final int RAW = 7000, EXTRA = 7001, PRR_ITEM = 104934, CONSUMER = 4001, HELPER = 4002, ROOT = 4000;
    private static final Map<Integer, PriceQuote> PRICES = Map.of(RAW, new PriceQuote(1, 2), EXTRA, new PriceQuote(1, 2));
    private static final ForgeSettings SETTINGS = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, false,
            ForgeSettings.DailyMode.CRAFT, false, ForgeSettings.ReduceMode.NO);
    private static final ForgeProjectPlanner.Context ANN = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15131, 98765, 98766));

    private final Recipe prr = new Recipe(15131, PRR_ITEM, 1, 0, "", List.of(new Ingredient(RAW, 30)));
    private final Recipe helper = new Recipe(98766, HELPER, 1, 0, "", List.of(new Ingredient(CONSUMER, 50)),
            List.of(), RecipeSource.ORDINARY);
    private final Recipe root = new Recipe(-21, ROOT, 1, 0, "",
            List.of(new Ingredient(CONSUMER, 1), new Ingredient(HELPER, 1)), List.of(), RecipeSource.MYSTIC_FORGE);

    private static Recipe consumer(List<Ingredient> inputs) {
        return new Recipe(98765, CONSUMER, 100, 0, "", inputs, List.of(new CurrencyRequirement(61, 150)), RecipeSource.ORDINARY);
    }

    private record Result(ForgeProjectPlanner.Project project, double percent) { }

    private Result run(Recipe consumer, Map<Integer, Integer> owned) {
        var planner = new ForgeProjectPlanner(List.of(prr, consumer, helper, root), SETTINGS);
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var project = planner.plan(root, 1, false, inv, ANN, Map.of(61, 0L), PRICES, SETTINGS);
        return new Result(project, planner.materialProgress(root, 1, inv, ANN, Map.of(61, 0L), project).percent());
    }

    @Test
    void oneConsumerBatchSharedByRootAndHelperWithFullRawStockIsFullyReady() {
        var r = run(consumer(List.of()), Map.of(RAW, 30));
        assertTrue(r.project().complete());
        assertEquals(150, r.project().notes().needed(), "ONE batch's Notes only, not a second simulated batch");
        assertEquals(1, r.project().notes().containersNeeded());
        assertEquals(100.0, r.percent());
    }

    @Test
    void halfTheRawStockIsHalfReadyNotAQuarter() {
        assertEquals(50.0, run(consumer(List.of()), Map.of(RAW, 15)).percent());
    }

    @Test
    void sharedSurplusKeepsTheProducingBatchCoverageWithoutDebitingARawInputTwice() {
        // Notes half owned (15/30 Raw) and the consumer's own raw input fully owned: the batch is 75% covered.
        var withExtra = consumer(List.of(new Ingredient(EXTRA, 1)));
        var r = run(withExtra, Map.of(RAW, 15, EXTRA, 1));
        assertEquals(75.0, r.percent(), "the helper shares the same 75% batch; EXTRA is spent once");
        // Only ONE EXTRA is owned and ONE batch is planned: the helper's 50 draw the surplus and need no second one.
        assertEquals(100.0, run(withExtra, Map.of(RAW, 30, EXTRA, 1)).percent());
    }

    private Result runHelper150(Recipe consumer, Map<Integer, Integer> owned) {
        Recipe bigHelper = new Recipe(98766, HELPER, 1, 0, "", List.of(new Ingredient(CONSUMER, 150)), List.of(), RecipeSource.ORDINARY);
        var planner = new ForgeProjectPlanner(List.of(prr, consumer, bigHelper, root), SETTINGS);
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var project = planner.plan(root, 1, false, inv, ANN, Map.of(61, 0L), PRICES, SETTINGS);
        return new Result(project, planner.materialProgress(root, 1, inv, ANN, Map.of(61, 0L), project).percent());
    }

    @Test
    void partiallyReadySurplusPlusFurtherBatchIsWeightedByPhysicalUnits() {
        // Helper needs 150: the root's 1 + helper's 150 = two consumer batches (Notes 300, Raw 60), Raw30 owned.
        // Every batch shares the same 50% Notes coverage, so the helper (99 surplus units at 50% + 51 new units at 50%) is 50%.
        var r = runHelper150(consumer(List.of()), Map.of(RAW, 30));
        assertEquals(2, r.project().steps().stream().filter(s -> s.recipeId() == 98765).findFirst().orElseThrow().crafts());
        assertEquals(300, r.project().notes().needed());
        assertEquals(50.0, r.percent(), "not 58.2: the unready part of the scheduled surplus is not credited twice");
        assertEquals(100.0, runHelper150(consumer(List.of()), Map.of(RAW, 60)).percent());
    }

    @Test
    void mixedReadinessSurplusPlusNewBatchStaysBelowReady() {
        // Raw 45 of 60 -> Notes 75% covered; EXTRA is NOT owned so the consumer batches' EXTRA share is 0: each batch
        // is (0 + 0.75)/2 = 37.5%, and every unit (surplus or new) carries that coverage.
        var withExtra = consumer(List.of(new Ingredient(EXTRA, 1)));
        var r = runHelper150(withExtra, Map.of(RAW, 45));
        assertEquals(37.5, r.percent());
        assertTrue(r.percent() < 100.0);
    }
}
