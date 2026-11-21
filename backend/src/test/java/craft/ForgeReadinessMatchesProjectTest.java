package craft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Readiness aggregate and root table must describe the same resolved project (root reduction and Note producer). */
class ForgeReadinessMatchesProjectTest {
    private static final int GOAL = 900, ORE = 3000, NOTE = 61, PRR = 104934, A_IN = 5001, B_IN = 5002;
    private static final ForgeProjectPlanner.Context NO_CTX = new ForgeProjectPlanner.Context(
            List.of(new CharacterCraftingProfile("Ann", Map.of("Artificer", 500))), java.util.Set.of(15120, 15131));

    private static ForgeSettings settings(ForgeSettings.PriceMode mode, boolean useOwn, ForgeSettings.ReduceMode reduce) {
        return new ForgeSettings(mode, useOwn, false, ForgeSettings.DailyMode.CRAFT, false, reduce);
    }

    // ---- root reduction -------------------------------------------------------------------------------------

    private record Case(String label, ForgeProjectPlanner.Inventory inv) { }

    private static List<Case> rootCases() {
        return List.of(
                new Case("sellable", new ForgeProjectPlanner.Inventory(Map.of(GOAL, 1), Map.of(), Map.of())),
                new Case("account-bound", new ForgeProjectPlanner.Inventory(Map.of(), Map.of(GOAL, 1), Map.of())),
                new Case("character-bound", new ForgeProjectPlanner.Inventory(Map.of(), Map.of(), Map.of("Ann", Map.of(GOAL, 1)))),
                new Case("one sellable + one bound", new ForgeProjectPlanner.Inventory(Map.of(GOAL, 1), Map.of(), Map.of("Ann", Map.of(GOAL, 1)))));
    }

    @ParameterizedTest
    @EnumSource(ForgeSettings.ReduceMode.class)
    void readinessAndRootTableUseTheProjectsOwnedRootReduction(ForgeSettings.ReduceMode reduce) {
        Recipe root = new Recipe(-1, GOAL, 1, 0, "", List.of(new Ingredient(ORE, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        ForgeProjectPlanner planner = LegacyForge.planner(List.of(root));
        for (Case c : rootCases()) {
            var s = settings(ForgeSettings.PriceMode.INSTANT_BUY, true, reduce);
            var project = planner.plan(root, 2, false, c.inv(), ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(), s);
            String label = reduce + "/" + c.label();
            var progress = planner.materialProgress(root, 2, c.inv(), ForgeProjectPlanner.Context.NONE, Map.of(), project);
            var table = planner.recipeTree(root, 2, c.inv(), ForgeProjectPlanner.Context.NONE, Map.of(), true, reduce);
            long owned = project.rootOwned();
            assertEquals(owned, table.acquired(), label + ": root table consumes exactly the planned owned root copies");
            assertEquals((int) (owned * 100 / 2), table.progressPercent(), label);
            // 2 wanted; ore is never owned, so only owned root copies add readiness (1 of 2 -> half)
            assertEquals(owned == 2 ? 100.0 : owned == 1 ? 50.0 : 0.0, progress.percent(), label + ": readiness agrees");
            long planned = Math.ceilDiv(2 - owned, 1L);
            assertEquals(planned, project.batches(), label + ": the planner crafts only what is not owned");
        }
    }

    @Test
    void rootReductionExpectationsPerModeAreLiteral() {
        Recipe root = new Recipe(-1, GOAL, 1, 0, "", List.of(new Ingredient(ORE, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        ForgeProjectPlanner planner = LegacyForge.planner(List.of(root));
        var sellableOnly = rootCases().get(0).inv();
        var boundOnly = rootCases().get(1).inv();
        long[][] expected = {{0, 0}, {1, 1}, {1, 0}, {0, 1}}; // NO, YES, TRADABLE, UNTRADABLE x {sellable, bound}
        ForgeSettings.ReduceMode[] modes = ForgeSettings.ReduceMode.values();
        for (int i = 0; i < modes.length; i++) {
            var s = settings(ForgeSettings.PriceMode.INSTANT_BUY, true, modes[i]);
            var a = planner.plan(root, 1, false, sellableOnly, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(), s);
            var b = planner.plan(root, 1, false, boundOnly, ForgeProjectPlanner.Context.NONE, Map.of(), Map.of(), s);
            assertEquals(expected[i][0], a.rootOwned(), modes[i] + " sellable");
            assertEquals(expected[i][1], b.rootOwned(), modes[i] + " bound");
            assertEquals(expected[i][0] == 1 ? 100.0 : 0.0,
                    planner.materialProgress(root, 1, sellableOnly, ForgeProjectPlanner.Context.NONE, Map.of(), a).percent(), modes[i] + " sellable bar");
            assertEquals(expected[i][1] == 1 ? 100.0 : 0.0,
                    planner.materialProgress(root, 1, boundOnly, ForgeProjectPlanner.Context.NONE, Map.of(), b).percent(), modes[i] + " bound bar");
        }
    }

    // ---- selected Research Note producer --------------------------------------------------------------------

    private final Recipe noteRoot = new Recipe(-7, GOAL, 1, 0, "", List.of(), List.of(new CurrencyRequirement(NOTE, 150)), RecipeSource.MYSTIC_FORGE);
    private final Recipe prrA = new Recipe(15120, PRR, 1, 0, "Artificer", List.of(new Ingredient(A_IN, 4)));
    private final Recipe prrB = new Recipe(15131, PRR, 1, 0, "Artificer", List.of(new Ingredient(B_IN, 3)));
    private final ForgeProjectPlanner planner = LegacyForge.planner(List.of(noteRoot, prrA, prrB));

    /** A: bid 5 / ask 100 per input (x4); B: bid 50 / ask 10 (x3) -> instant buy picks B (30c vs 400c), buy order picks A (20c vs 150c). */
    private static final Map<Integer, PriceQuote> PRICES = Map.of(A_IN, new PriceQuote(5, 100), B_IN, new PriceQuote(50, 10));

    private record Result(ForgeProjectPlanner.Project project, double percent) { }

    private Result run(ForgeSettings.PriceMode mode, Map<Integer, Integer> owned, Map<Integer, Long> wallet) {
        var inv = new ForgeProjectPlanner.Inventory(owned, Map.of(), Map.of());
        var project = planner.plan(noteRoot, 1, false, inv, NO_CTX, wallet, PRICES, settings(mode, true, ForgeSettings.ReduceMode.NO));
        return new Result(project, planner.materialProgress(noteRoot, 1, inv, NO_CTX, wallet, project).percent());
    }

    @Test
    void instantBuyPlansTheCheaperVariantAndReadinessFollowsIt() {
        var ownsAInputs = run(ForgeSettings.PriceMode.INSTANT_BUY, Map.of(A_IN, 4), Map.of((int) NOTE, 0L));
        assertEquals(15131, ownsAInputs.project().notes().producerRecipeId());
        assertEquals(0.0, ownsAInputs.percent(), "the plan buys variant B inputs; owning variant A inputs is not readiness");
        var ownsBInputs = run(ForgeSettings.PriceMode.INSTANT_BUY, Map.of(B_IN, 3), Map.of((int) NOTE, 0L));
        assertEquals(100.0, ownsBInputs.percent());
        var half = run(ForgeSettings.PriceMode.INSTANT_BUY, Map.of(B_IN, 1), Map.of((int) NOTE, 0L));
        assertEquals(33.3, half.percent(), "1 of 3 selected-variant inputs owned");
    }

    @Test
    void buyOrderPlansTheOtherVariantAndReadinessFollowsIt() {
        var ownsAInputs = run(ForgeSettings.PriceMode.BUY_ORDER, Map.of(A_IN, 4), Map.of((int) NOTE, 0L));
        assertEquals(15120, ownsAInputs.project().notes().producerRecipeId());
        assertEquals(100.0, ownsAInputs.percent());
        var ownsBInputs = run(ForgeSettings.PriceMode.BUY_ORDER, Map.of(B_IN, 3), Map.of((int) NOTE, 0L));
        assertEquals(0.0, ownsBInputs.percent(), "the buy-order plan uses variant A; B inputs do not count");
    }

    @Test
    void walletCoveringTheNotesNeedsNoProducerAndIsFullyReady() {
        var r = run(ForgeSettings.PriceMode.INSTANT_BUY, Map.of(), Map.of((int) NOTE, 150L));
        assertEquals(100.0, r.percent());
    }

    @Test
    void unknownWalletCountsOnlyOwnedInputsOfTheSelectedVariantAndPlansNoProduction() {
        var progress = run(ForgeSettings.PriceMode.INSTANT_BUY, Map.of(A_IN, 4), null);
        assertNull(progress.project().notes().producerRecipeId(), "an unknown wallet plans no production");
        assertTrue(progress.percent() < 100.0);
        var inv = new ForgeProjectPlanner.Inventory(Map.of(B_IN, 3), Map.of(), Map.of());
        var project = planner.plan(noteRoot, 1, false, inv, NO_CTX, null, PRICES, settings(ForgeSettings.PriceMode.INSTANT_BUY, true, ForgeSettings.ReduceMode.NO));
        var p = planner.materialProgress(noteRoot, 1, inv, NO_CTX, null, project);
        assertEquals(100.0, p.percent(), "owning the SELECTED variant's inputs is what the existing unknown-wallet rule counts");
        var wrong = planner.plan(noteRoot, 1, false, new ForgeProjectPlanner.Inventory(Map.of(A_IN, 4), Map.of(), Map.of()), NO_CTX, null, PRICES,
                settings(ForgeSettings.PriceMode.INSTANT_BUY, true, ForgeSettings.ReduceMode.NO));
        assertTrue(planner.materialProgress(noteRoot, 1, new ForgeProjectPlanner.Inventory(Map.of(A_IN, 4), Map.of(), Map.of()),
                NO_CTX, null, wrong).percent() < p.percent());
    }
}
