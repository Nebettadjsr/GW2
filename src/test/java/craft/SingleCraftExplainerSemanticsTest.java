package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.noQuote;
import static craft.CraftTestFixtures.profile;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantic coverage for the single-craft explanation
 * (TARGET_ARCHITECTURE.md section 13.3, DOMAIN_SPEC.md sections 10-12, 17, 21, 42 and 48):
 * the facts it exposes must be the ones the authoritative resolver actually decided.
 *
 * <p>Database-independent: every recipe, inventory pool and quote is supplied directly.
 */
class SingleCraftExplainerSemanticsTest {

    private static final int FINAL_ITEM = 999;
    private static final int INTERMEDIATE_ITEM = 100;
    private static final int SECOND_INTERMEDIATE_ITEM = 101;
    private static final int RAW_ITEM = 200;

    /** Charged Quartz Crystal - a daily-limited output (DOMAIN_SPEC.md section 31). */
    private static final int DAILY_ITEM = 43772;

    private final SingleCraftExplainer explainer = new SingleCraftExplainer();

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying, int maxBuyCopper) {
        return new CraftingSettings(useOwnMats, allowBuying, maxBuyCopper, false, false, false);
    }

    // ---------------------------------------------------------------- identity & split sourcing

    @Test
    void shouldReportSplitInventoryAndPurchaseSourcingWithInclusiveCosts() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(INTERMEDIATE_ITEM, 5)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(INTERMEDIATE_ITEM, 2),
                Map.of(),
                // instant buy costs sellUnit=10, instant sell yields buyUnit=7
                Map.of(INTERMEDIATE_ITEM, quote(7, 10)),
                settings(true, true, 0),
                Set.of(1));

        assertTrue(explanation.available());
        assertEquals(1, explanation.recipeId());

        CraftTraceNode rootNode = explanation.root();
        assertEquals(FINAL_ITEM, rootNode.itemId());
        assertEquals(1, rootNode.requestedQuantity());
        assertEquals(1, rootNode.craftedQuantity());
        assertEquals(Integer.valueOf(1), rootNode.recipeId());
        assertEquals(1, rootNode.craftCount());
        assertEquals(1, rootNode.producedQuantity());
        assertEquals(List.of(AcquisitionMethod.CRAFT), rootNode.methods());
        assertEquals(List.of(), rootNode.states());
        assertEquals(List.of(), rootNode.blockedReasons());

        CraftTraceNode ingredientNode = single(rootNode);
        assertEquals(INTERMEDIATE_ITEM, ingredientNode.itemId());
        assertEquals(5, ingredientNode.requestedQuantity());
        assertEquals(2, ingredientNode.inventoryQuantity());
        assertEquals(3, ingredientNode.boughtQuantity());
        assertEquals(0, ingredientNode.craftedQuantity());
        assertEquals(0, ingredientNode.missingQuantity());
        assertEquals(List.of(AcquisitionMethod.INVENTORY, AcquisitionMethod.BUY), ingredientNode.methods());
        assertNull(ingredientNode.recipeId());
        assertEquals(List.of(), ingredientNode.children());

        // 3 bought x 10 cash, 2 owned x 7 opportunity cost.
        assertEquals(Integer.valueOf(30), ingredientNode.cashCostCopper());
        assertEquals(Integer.valueOf(14), ingredientNode.opportunityCostCopper());
        assertEquals(Integer.valueOf(44), ingredientNode.effectiveCostCopper());

        // The ancestor already includes the descendant; adding them again would double-count.
        assertEquals(Integer.valueOf(30), rootNode.cashCostCopper());
        assertEquals(Integer.valueOf(14), rootNode.opportunityCostCopper());
        assertEquals(Integer.valueOf(44), rootNode.effectiveCostCopper());

        assertConserved(rootNode);
    }

    @Test
    void shouldReportBatchSurplusSeparatelyFromTheQuantityUsed() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(INTERMEDIATE_ITEM, 3)));
        // Produces two per execution, so three needed means two executions and four produced.
        Recipe batch = recipe(2, INTERMEDIATE_ITEM, 2, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root, batch),
                Map.of(RAW_ITEM, 2),
                Map.of(),
                Map.of(RAW_ITEM, quote(6, 9)),
                settings(true, false, 0),
                Set.of(1, 2));

        CraftTraceNode intermediate = single(explanation.root());
        assertEquals(3, intermediate.requestedQuantity());
        assertEquals(3, intermediate.craftedQuantity());
        assertEquals(Integer.valueOf(2), intermediate.recipeId());
        assertEquals(2, intermediate.craftCount());
        assertEquals(4, intermediate.producedQuantity());
        assertEquals(1, intermediate.surplusQuantity());

        CraftTraceNode raw = single(intermediate);
        assertEquals(2, raw.requestedQuantity());
        assertEquals(2, raw.inventoryQuantity());
        assertEquals(Integer.valueOf(12), raw.opportunityCostCopper());

        assertConserved(explanation.root());
    }

    @Test
    void shouldKeepRepeatedItemsInDistinctBranchesAsDistinctOccurrences() {
        Recipe root = recipe(1, FINAL_ITEM,
                List.of(ingredient(INTERMEDIATE_ITEM, 1), ingredient(SECOND_INTERMEDIATE_ITEM, 1)));
        Recipe first = recipe(2, INTERMEDIATE_ITEM, List.of(ingredient(RAW_ITEM, 1)));
        Recipe second = recipe(3, SECOND_INTERMEDIATE_ITEM, List.of(ingredient(RAW_ITEM, 2)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root, first, second),
                Map.of(RAW_ITEM, 3),
                Map.of(),
                Map.of(RAW_ITEM, quote(4, 9)),
                settings(true, false, 0),
                Set.of(1, 2, 3));

        List<CraftTraceNode> branches = explanation.root().children();
        assertEquals(2, branches.size());

        CraftTraceNode rawUnderFirst = single(branches.get(0));
        CraftTraceNode rawUnderSecond = single(branches.get(1));

        assertEquals(RAW_ITEM, rawUnderFirst.itemId());
        assertEquals(RAW_ITEM, rawUnderSecond.itemId());
        assertEquals(1, rawUnderFirst.requestedQuantity());
        assertEquals(2, rawUnderSecond.requestedQuantity());

        // The same owned quantity is not counted twice: 1 + 2 of the three owned units.
        assertEquals(1, rawUnderFirst.inventoryQuantity());
        assertEquals(2, rawUnderSecond.inventoryQuantity());
        assertEquals(Integer.valueOf(12), explanation.root().opportunityCostCopper());

        assertConserved(explanation.root());
    }

    // ---------------------------------------------------------------- speculation and rollback

    @Test
    void shouldNotLeakTheLosingCraftPathWhenBuyingWins() {
        // DOMAIN_SPEC.md section 22: crafting the intermediate consumes an owned raw material
        // worth 80, buying it costs 60, so buying wins and the craft attempt is rolled back -
        // leaving the owned raw material for the root recipe's own requirement.
        Recipe root = recipe(1, FINAL_ITEM,
                List.of(ingredient(INTERMEDIATE_ITEM, 1), ingredient(RAW_ITEM, 1)));
        Recipe losing = recipe(2, INTERMEDIATE_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root, losing),
                Map.of(RAW_ITEM, 1),
                Map.of(),
                Map.of(INTERMEDIATE_ITEM, quote(null, 60), RAW_ITEM, quote(80, 5)),
                settings(true, true, 0),
                Set.of(1, 2));

        List<CraftTraceNode> children = explanation.root().children();
        assertEquals(2, children.size());

        CraftTraceNode bought = children.get(0);
        assertEquals(INTERMEDIATE_ITEM, bought.itemId());
        assertEquals(List.of(AcquisitionMethod.BUY), bought.methods());
        assertEquals(1, bought.boughtQuantity());
        assertNull(bought.recipeId(), "the rejected craft path must not appear as a selected recipe");
        assertEquals(0, bought.craftCount());
        assertEquals(List.of(), bought.children(), "the losing path's requirements must not leak");
        assertEquals(Integer.valueOf(60), bought.cashCostCopper());
        assertEquals(Integer.valueOf(0), bought.opportunityCostCopper());

        CraftTraceNode ownRaw = children.get(1);
        assertEquals(RAW_ITEM, ownRaw.itemId());
        assertEquals(1, ownRaw.inventoryQuantity(),
                "the speculative craft's inventory consumption must have been rolled back");
        assertEquals(Integer.valueOf(80), ownRaw.opportunityCostCopper());

        assertEquals(Integer.valueOf(60), explanation.root().cashCostCopper());
        assertEquals(Integer.valueOf(80), explanation.root().opportunityCostCopper());
        assertConserved(explanation.root());
    }

    @Test
    void shouldNotLeakTheLosingCharacterAttemptOfACoordinatedCraft() {
        // Both characters may perform the recipe; only Bob owns the soulbound ingredient, so Ann's
        // blocked attempt loses and must leave nothing behind (DOMAIN_SPEC.md sections 2.2.1/11.1).
        Recipe root = new Recipe(1, FINAL_ITEM, 1, 400, "Artificer", List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainCoordinated(
                root,
                List.of(root),
                Map.of(),
                Map.of(),
                Map.of("Ann", Map.of(), "Bob", Map.of(RAW_ITEM, 1)),
                List.of(profile("Ann", "Artificer", 400), profile("Bob", "Artificer", 400)),
                Map.of(RAW_ITEM, quote(50, 70)),
                settings(true, false, 0),
                Set.of(1));

        CraftTraceNode rootNode = explanation.root();
        assertEquals("Bob", rootNode.characterName());
        assertEquals(1, rootNode.craftedQuantity());
        assertEquals(List.of(), rootNode.blockedReasons());

        CraftTraceNode raw = single(rootNode);
        assertEquals(1, raw.inventoryQuantity());
        assertEquals(0, raw.missingQuantity());
        // Soulbound quantity carries no Trading Post opportunity cost.
        assertEquals(Integer.valueOf(0), raw.opportunityCostCopper());
        assertEquals(Integer.valueOf(0), rootNode.effectiveCostCopper());

        assertConserved(rootNode);
    }

    @Test
    void shouldReportTheAssignedCharacterOfACoordinatedCraft() {
        Recipe root = new Recipe(1, FINAL_ITEM, 1, 400, "Artificer", List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainCoordinated(
                root,
                List.of(root),
                Map.of(RAW_ITEM, 1),
                Map.of(),
                Map.of(),
                List.of(profile("Tailor Only", "Tailor", 400), profile("Ann", "Artificer", 400)),
                Map.of(RAW_ITEM, quote(11, 13)),
                settings(true, false, 0),
                Set.of(1));

        assertEquals("Ann", explanation.root().characterName());
        assertEquals(Integer.valueOf(11), explanation.root().opportunityCostCopper());
    }

    @Test
    void shouldBlockACoordinatedCraftWhoseRecipeIsNotInTheAllowedSet() {
        Recipe root = new Recipe(1, FINAL_ITEM, 1, 400, "Artificer", List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainCoordinated(
                root,
                List.of(root),
                Map.of(RAW_ITEM, 1),
                Map.of(),
                Map.of(),
                List.of(profile("Ann", "Artificer", 400)),
                Map.of(RAW_ITEM, quote(11, 13)),
                settings(true, false, 0),
                Set.of());

        assertTrue(explanation.available(), "a blocked first craft is still an explanation");

        CraftTraceNode rootNode = explanation.root();
        assertEquals(List.of(BlockedReason.RECIPE_NOT_ALLOWED), rootNode.blockedReasons());
        assertEquals(List.of(ResolutionState.BLOCKED), rootNode.states());
        assertEquals(1, rootNode.missingQuantity());
        assertEquals(List.of(), rootNode.methods());
        assertEquals(List.of(), rootNode.children());
        assertNull(rootNode.effectiveCostCopper());
    }

    // ---------------------------------------------------------------- states and blocked reasons

    @Test
    void shouldTerminateACycleWithAFiniteBlockedNode() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(INTERMEDIATE_ITEM, 1)));
        Recipe forward = recipe(2, INTERMEDIATE_ITEM, List.of(ingredient(SECOND_INTERMEDIATE_ITEM, 1)));
        Recipe back = recipe(3, SECOND_INTERMEDIATE_ITEM, List.of(ingredient(INTERMEDIATE_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root, forward, back),
                Map.of(),
                Map.of(),
                Map.of(),
                settings(false, false, 0),
                Set.of(1, 2, 3));

        assertTrue(explanation.available());

        CraftTraceNode first = single(explanation.root());
        CraftTraceNode second = single(first);
        CraftTraceNode repeated = single(second);

        assertEquals(INTERMEDIATE_ITEM, first.itemId());
        assertEquals(SECOND_INTERMEDIATE_ITEM, second.itemId());
        assertEquals(INTERMEDIATE_ITEM, repeated.itemId());
        assertEquals(List.of(), repeated.children(), "the cycle must terminate");
        assertTrue(repeated.blockedReasons().contains(BlockedReason.CYCLE_DETECTED));
        assertTrue(repeated.states().contains(ResolutionState.BLOCKED));
        assertNull(repeated.effectiveCostCopper());
    }

    @Test
    void shouldReportPriceUnavailableWithoutTurningItIntoAZeroCost() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(),
                Map.of(),
                Map.of(RAW_ITEM, noQuote()),
                settings(false, true, 0),
                Set.of(1));

        CraftTraceNode raw = single(explanation.root());
        assertEquals(List.of(BlockedReason.PRICE_UNAVAILABLE), raw.blockedReasons());
        assertEquals(List.of(ResolutionState.BLOCKED, ResolutionState.PRICE_UNAVAILABLE), raw.states());
        assertEquals(1, raw.missingQuantity());
        assertEquals(List.of(), raw.methods());
        assertNull(raw.cashCostCopper(), "an unknown price must never be reported as zero cost");
        assertNull(explanation.root().cashCostCopper());
    }

    @Test
    void shouldMarkAZeroValuedOwnedQuantityAsUnvaluedRatherThanFree() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 2)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(RAW_ITEM, 2),
                Map.of(),
                Map.of(RAW_ITEM, noQuote()),
                settings(true, false, 0),
                Set.of(1));

        CraftTraceNode raw = single(explanation.root());
        assertEquals(2, raw.inventoryQuantity());
        assertEquals(List.of(ResolutionState.UNVALUED_NONTRADEABLE), raw.states());
        // A domain-established zero, unlike the null of a requirement with no complete value.
        assertEquals(Integer.valueOf(0), raw.opportunityCostCopper());
        assertEquals(Integer.valueOf(0), raw.effectiveCostCopper());
        assertEquals(List.of(), raw.blockedReasons());
    }

    @Test
    void shouldNotMarkBoundQuantityAsUnvalued() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(),
                Map.of(RAW_ITEM, 1),
                Map.of(RAW_ITEM, quote(30, 40)),
                settings(true, false, 0),
                Set.of(1));

        CraftTraceNode raw = single(explanation.root());
        assertEquals(1, raw.inventoryQuantity());
        // Bound quantity is deliberately exempt from opportunity cost; that is not "unvalued".
        assertEquals(List.of(), raw.states());
        assertEquals(Integer.valueOf(0), raw.opportunityCostCopper());
    }

    @Test
    void shouldReportTheDailyLimitOfARepeatedDailyCraft() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(DAILY_ITEM, 2)));
        Recipe daily = recipe(2, DAILY_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root, daily),
                Map.of(RAW_ITEM, 5),
                Map.of(),
                Map.of(RAW_ITEM, quote(3, 4)),
                settings(true, false, 0),
                Set.of(1, 2));

        assertTrue(explanation.available());

        CraftTraceNode dailyNode = single(explanation.root());
        assertEquals(DAILY_ITEM, dailyNode.itemId());
        assertEquals(2, dailyNode.requestedQuantity());
        assertEquals(2, dailyNode.missingQuantity());
        assertTrue(dailyNode.blockedReasons().contains(BlockedReason.DAILY_LIMIT));
        assertTrue(dailyNode.states().contains(ResolutionState.DAILY_LIMIT));
        assertEquals(Integer.valueOf(2), dailyNode.recipeId());
        assertEquals(0, dailyNode.craftCount(), "the rolled-back attempt contributed nothing");
        assertEquals(0, dailyNode.producedQuantity());
        assertNull(dailyNode.effectiveCostCopper());
    }

    @Test
    void shouldReportABudgetRejectedCraftAsBlockedWithItsEstablishedCost() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation explanation = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(),
                Map.of(),
                Map.of(RAW_ITEM, quote(null, 100)),
                settings(false, true, 50),
                Set.of(1));

        assertTrue(explanation.available());

        CraftTraceNode rootNode = explanation.root();
        assertEquals(List.of(BlockedReason.INSUFFICIENT_BUDGET), rootNode.blockedReasons());
        assertTrue(rootNode.states().contains(ResolutionState.BLOCKED));
        // The requirement itself resolved, so its cost is established rather than partial.
        assertEquals(Integer.valueOf(100), rootNode.cashCostCopper());
        assertEquals(1, single(rootNode).boughtQuantity());
    }

    @Test
    void shouldDistinguishAnAbsentResultFromABlockedTree() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation blocked = explainer.explainIndividual(
                root,
                List.of(root),
                Map.of(),
                Map.of(),
                Map.of(),
                settings(false, false, 0),
                Set.of(1));

        assertTrue(blocked.available());
        assertNotNull(blocked.root());
        assertEquals(List.of(BlockedReason.BUYING_DISABLED), blocked.root().blockedReasons());

        SingleCraftExplanation absent = SingleCraftExplanation.unavailable(1, FINAL_ITEM, 1);
        assertFalse(absent.available());
        assertNull(absent.root(), "an absent result must not become a fabricated empty tree");
    }

    // ---------------------------------------------------------------- helpers

    private static CraftTraceNode single(CraftTraceNode node) {
        assertEquals(1, node.children().size(), "expected exactly one child of item " + node.itemId());
        return node.children().get(0);
    }

    /** DOMAIN_SPEC.md section 48: a requirement's sources must account for exactly what it asked. */
    private static void assertConserved(CraftTraceNode node) {
        assertEquals(node.requestedQuantity(),
                node.inventoryQuantity() + node.craftedQuantity() + node.boughtQuantity()
                        + node.missingQuantity(),
                "quantity conservation for item " + node.itemId());

        for (CraftTraceNode child : node.children()) {
            assertConserved(child);
        }
    }
}
