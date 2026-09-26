package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOMAIN_SPEC.md section 2.1.1 / UD-009 / UD-010: the "Allow non-Trading-Post materials" calculation
 * rule in the authoritative resolver.
 *
 * <p>Nontradeability is supplied as a classification fact ({@link MaterialTradeability}) in every
 * test, never derived from a quote: several fixtures deliberately give a <em>non</em>-classified item
 * no usable quote to prove its missing price still produces
 * {@link BlockedReason#PRICE_UNAVAILABLE}, and one gives a classified item a full quote to prove the
 * classification - not the price - is what restricts it.
 */
class CraftingResolverNonTradeableMaterialTest {

    private static final int OUTPUT = 100;
    /** Tradeable, quoted, and never restricted. */
    private static final int TRADEABLE_MAT = 200;
    /** Classified as non-Trading-Post by the calculation's inputs. */
    private static final int NON_TP_MAT = 300;
    /** Classified as non-Trading-Post and craftable from {@link #TRADEABLE_MAT}. */
    private static final int NON_TP_INTERMEDIATE = 400;
    /** Ordinary tradeable intermediate whose own recipe consumes {@link #NON_TP_MAT}. */
    private static final int TRADEABLE_INTERMEDIATE = 500;

    private static final Recipe FROM_NON_TP = CraftTestFixtures.recipe(
            1, OUTPUT, List.of(CraftTestFixtures.ingredient(TRADEABLE_MAT, 2),
                    CraftTestFixtures.ingredient(NON_TP_MAT, 1)));

    /** A more expensive alternative for the same output that consumes only tradeable materials. */
    private static final Recipe FROM_TRADEABLE_ONLY = CraftTestFixtures.recipe(
            2, OUTPUT, List.of(CraftTestFixtures.ingredient(TRADEABLE_MAT, 9)));

    private static final Recipe FROM_NON_TP_INTERMEDIATE = CraftTestFixtures.recipe(
            3, OUTPUT, List.of(CraftTestFixtures.ingredient(NON_TP_INTERMEDIATE, 1)));

    private static final Recipe CRAFT_NON_TP_INTERMEDIATE = CraftTestFixtures.recipe(
            4, NON_TP_INTERMEDIATE, List.of(CraftTestFixtures.ingredient(TRADEABLE_MAT, 1)));

    private static final Recipe FROM_TRADEABLE_INTERMEDIATE = CraftTestFixtures.recipe(
            5, OUTPUT, List.of(CraftTestFixtures.ingredient(TRADEABLE_INTERMEDIATE, 1)));

    private static final Recipe CRAFT_TRADEABLE_INTERMEDIATE = CraftTestFixtures.recipe(
            6, TRADEABLE_INTERMEDIATE, List.of(CraftTestFixtures.ingredient(NON_TP_MAT, 1)));

    /** The non-Trading-Post items have no quote; the classification is what names them. */
    private static final Map<Integer, PriceQuote> TP = Map.of(
            OUTPUT, CraftTestFixtures.quote(900, 1000),
            TRADEABLE_MAT, CraftTestFixtures.quote(10, 12),
            NON_TP_MAT, CraftTestFixtures.noQuote(),
            NON_TP_INTERMEDIATE, CraftTestFixtures.noQuote(),
            TRADEABLE_INTERMEDIATE, CraftTestFixtures.noQuote());

    private static Map<Integer, List<Recipe>> byOutput(Recipe... recipes) {
        Map<Integer, List<Recipe>> out = new java.util.HashMap<>();
        for (Recipe r : recipes) {
            out.computeIfAbsent(r.outputItemId, k -> new java.util.ArrayList<>()).add(r);
        }
        return out;
    }

    private static Set<Integer> allowed(Recipe... recipes) {
        Set<Integer> ids = new java.util.HashSet<>();
        for (Recipe r : recipes) ids.add(r.recipeId);
        return ids;
    }

    // ---------- option off: paths consuming a non-Trading-Post material are rejected ----------

    @Test
    void optionOff_rejectsPathConsumingAnOwnedUnboundNonTradeableMaterial() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), TP, CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.state(Map.of(NON_TP_MAT, 5, TRADEABLE_MAT, 10));

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, root.getBlockedReason());

        // Owning it is not permission to consume it, so nothing was taken from the pool - and the
        // rejected attempt's own consumption of the tradeable material was rolled back with it.
        assertEquals(5, state.inventory.getOrDefault(NON_TP_MAT, 0));
        assertEquals(10, state.inventory.getOrDefault(TRADEABLE_MAT, 0));
        assertTrue(state.missingToBuy.isEmpty());
        assertEquals(0, state.buyCostCopper);
    }

    @Test
    void optionOff_rejectsPathConsumingAnAccountBoundNonTradeableMaterial() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), TP, CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.stateWithBoundInventory(
                Map.of(TRADEABLE_MAT, 10), Map.of(NON_TP_MAT, 5));

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, root.getBlockedReason());
        assertEquals(5, state.boundInventory.getOrDefault(NON_TP_MAT, 0));
    }

    @Test
    void optionOff_rejectsPathCraftingANonTradeableIntermediateFromTradeableParts() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP_INTERMEDIATE, CRAFT_NON_TP_INTERMEDIATE), TP,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP_INTERMEDIATE, CRAFT_NON_TP_INTERMEDIATE),
                Set.of(NON_TP_INTERMEDIATE));
        PlanState state = CraftTestFixtures.state(Map.of(TRADEABLE_MAT, 50));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_NON_TP_INTERMEDIATE, ctx, state).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, root.getBlockedReason());
        // The intermediate was never crafted, so its tradeable ingredients stay untouched.
        assertEquals(50, state.inventory.getOrDefault(TRADEABLE_MAT, 0));
    }

    @Test
    void optionOff_rejectsPathWhoseDeeperIntermediateConsumesANonTradeableMaterial() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_TRADEABLE_INTERMEDIATE, CRAFT_TRADEABLE_INTERMEDIATE), TP,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_TRADEABLE_INTERMEDIATE, CRAFT_TRADEABLE_INTERMEDIATE),
                Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.state(Map.of(NON_TP_MAT, 5));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_TRADEABLE_INTERMEDIATE, ctx, state).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, root.getBlockedReason());
        assertEquals(5, state.inventory.getOrDefault(NON_TP_MAT, 0));
    }

    @Test
    void optionOff_reportsTheRestrictionAtTheMaterialItAppliesTo() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), TP, CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.state(Map.of(TRADEABLE_MAT, 10));

        // The tracing resolver is the one the selected-recipe explanation uses, so the requirement
        // the restriction applies to has to be reachable from the attempted craft.
        ResolvedNeed root = new CraftingResolver(true)
                .resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertNotNull(root.getTrace());
        ResolvedNeed attempt = root.getTrace().getCraftAttempt();
        assertNotNull(attempt, "the attempted craft explains the blocked requirement");

        ResolvedNeed restricted = null;
        for (ResolvedNeed child : attempt.getChildren()) {
            if (child.getItemId() == NON_TP_MAT) restricted = child;
        }

        assertNotNull(restricted, "the forbidden material is a requirement of the attempted craft");
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, restricted.getBlockedReason());
        assertEquals(1, restricted.getQtyBlocked());
        assertEquals(0, restricted.getQtyFromInventory());
        assertEquals(0, restricted.getQtyBought());
    }

    /**
     * The cheap candidate's cost estimate has to be establishable for it to win the section 30
     * comparison at all, so this map quotes the classified material too. That the classification
     * still restricts it is the point of
     * {@link #aClassifiedMaterialIsRestrictedEvenWhenItHasAFullQuote()}.
     */
    private static final Map<Integer, PriceQuote> TP_QUOTING_THE_CLASSIFIED_MATERIAL = Map.of(
            OUTPUT, CraftTestFixtures.quote(900, 1000),
            TRADEABLE_MAT, CraftTestFixtures.quote(10, 12),
            NON_TP_MAT, CraftTestFixtures.quote(7, 8));

    @Test
    void optionOff_stillEvaluatesAnAlternativePathThatAvoidsTheNonTradeableMaterial() {
        // Recipe 1 (2 tradeable + 1 non-TP, estimated 32) is the candidate the resolver prefers while
        // the option is on; recipe 2 (9 tradeable, estimated 108) is the dearer alternative. With the
        // option off the alternative must win rather than the requirement becoming unresolvable.
        PlannerContext optionOff = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP, FROM_TRADEABLE_ONLY), TP_QUOTING_THE_CLASSIFIED_MATERIAL,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP, FROM_TRADEABLE_ONLY), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.state(Map.of(NON_TP_MAT, 5, TRADEABLE_MAT, 20));

        ResolvedNeed root = new CraftingResolver(true)
                .resolveOneCraft(FROM_NON_TP, optionOff, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(BlockedReason.NONE, root.getBlockedReason());
        assertEquals(FROM_TRADEABLE_ONLY.recipeId, selectedRecipeIdOf(root));
        // 9 tradeable units consumed by the alternative, and the forbidden material untouched.
        assertEquals(11, state.inventory.getOrDefault(TRADEABLE_MAT, 0));
        assertEquals(5, state.inventory.getOrDefault(NON_TP_MAT, 0));

        // The same inputs with the option on select the candidate that consumes the material, which
        // is what makes the choice above the rule's effect rather than the cost comparison's.
        PlannerContext optionOn = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP, FROM_TRADEABLE_ONLY), TP_QUOTING_THE_CLASSIFIED_MATERIAL,
                CraftTestFixtures.defaultSettings(),
                allowed(FROM_NON_TP, FROM_TRADEABLE_ONLY), Set.of(NON_TP_MAT));
        ResolvedNeed permitted = new CraftingResolver(true).resolveOneCraft(
                FROM_NON_TP, optionOn,
                CraftTestFixtures.state(Map.of(NON_TP_MAT, 5, TRADEABLE_MAT, 20))).getRoot();

        assertEquals(FROM_NON_TP.recipeId, selectedRecipeIdOf(permitted));
    }

    @Test
    void optionOff_leavesTheBudgetRestrictionOnATradeablePathInPlace() {
        // Nothing owned, buying allowed but capped below the tradeable purchase this path needs: the
        // material rule must neither reject the path nor replace the budget reason for it.
        CraftingSettings tinyBudget = new CraftingSettings(true, true, 5, false, false, false, false);
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_TRADEABLE_ONLY), TP, tinyBudget, allowed(FROM_TRADEABLE_ONLY),
                Set.of(NON_TP_MAT));

        RecipeSimulationResult simulation = new RecipeSimulator()
                .simulateRecipe(FROM_TRADEABLE_ONLY, ctx, CraftTestFixtures.emptyState());

        assertEquals(0, simulation.getCraftCount());
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, simulation.getBlockedReason());
    }

    /** The recipe the resolver actually selected for the root requirement, from its own trace. */
    private static int selectedRecipeIdOf(ResolvedNeed root) {
        assertNotNull(root.getTrace());
        ResolvedNeed attempt = root.getTrace().getCraftAttempt();
        assertNotNull(attempt);
        assertNotNull(attempt.getTrace());
        return attempt.getTrace().getSelectedRecipeId();
    }

    // ---------- option on: the decided enabled behavior, unchanged from before the option ----------

    @Test
    void optionOn_usesAnOwnedNonTradeableMaterialNormally() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), TP, CraftTestFixtures.defaultSettings(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.state(Map.of(NON_TP_MAT, 5, TRADEABLE_MAT, 10));

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(BlockedReason.NONE, root.getBlockedReason());
        assertEquals(4, state.inventory.getOrDefault(NON_TP_MAT, 0));
    }

    @Test
    void optionOn_craftsANonTradeableIntermediateNormally() {
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP_INTERMEDIATE, CRAFT_NON_TP_INTERMEDIATE), TP,
                CraftTestFixtures.defaultSettings(),
                allowed(FROM_NON_TP_INTERMEDIATE, CRAFT_NON_TP_INTERMEDIATE),
                Set.of(NON_TP_INTERMEDIATE));
        PlanState state = CraftTestFixtures.state(Map.of(TRADEABLE_MAT, 5));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_NON_TP_INTERMEDIATE, ctx, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(BlockedReason.NONE, root.getBlockedReason());
        assertEquals(4, state.inventory.getOrDefault(TRADEABLE_MAT, 0));
    }

    @Test
    void optionOn_leavesAnUnsatisfiableNonTradeableRequirementUnavailableWithoutBuyingIt() {
        // Nothing owned and no recipe for the non-Trading-Post material: the enabled option must not
        // invent an external acquisition for it.
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), TP, CraftTestFixtures.defaultSettings(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));
        PlanState state = CraftTestFixtures.emptyState();

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.PRICE_UNAVAILABLE, root.getBlockedReason());
        assertEquals(0, root.getQtyBought());
    }

    // ---------- the classification, and only the classification, decides ----------

    @Test
    void aTradeableMaterialWithNoUsableQuoteStaysPriceUnavailableWithTheOptionOff() {
        // Item 200 is quoted, item 500 is not - and neither is classified as non-Trading-Post. A
        // missing quote must therefore keep producing PRICE_UNAVAILABLE, not the new restriction.
        Recipe fromUnquotedTradeable = CraftTestFixtures.recipe(
                7, OUTPUT, List.of(CraftTestFixtures.ingredient(TRADEABLE_INTERMEDIATE, 1)));
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(fromUnquotedTradeable), TP,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(fromUnquotedTradeable), Set.of(NON_TP_MAT));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(fromUnquotedTradeable, ctx, CraftTestFixtures.emptyState()).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.PRICE_UNAVAILABLE, root.getBlockedReason());
    }

    @Test
    void aClassifiedMaterialIsRestrictedEvenWhenItHasAFullQuote() {
        Map<Integer, PriceQuote> quotedEverywhere = Map.of(
                OUTPUT, CraftTestFixtures.quote(900, 1000),
                TRADEABLE_MAT, CraftTestFixtures.quote(10, 12),
                NON_TP_MAT, CraftTestFixtures.quote(7, 8));
        PlannerContext ctx = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_NON_TP), quotedEverywhere,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_NON_TP), Set.of(NON_TP_MAT));

        ResolvedNeed root = new CraftingResolver()
                .resolveOneCraft(FROM_NON_TP, ctx, CraftTestFixtures.emptyState()).getRoot();

        assertFalse(root.isFullySatisfied());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, root.getBlockedReason());
        assertEquals(0, root.getBuyCostCopper());
    }

    @Test
    void aPurelyTradeablePathResolvesIdenticallyUnderBothValues() {
        PlannerContext optionOn = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_TRADEABLE_ONLY), TP, CraftTestFixtures.defaultSettings(),
                allowed(FROM_TRADEABLE_ONLY), Set.of(NON_TP_MAT));
        PlannerContext optionOff = CraftTestFixtures.contextWithNonTradeableItems(
                byOutput(FROM_TRADEABLE_ONLY), TP,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(),
                allowed(FROM_TRADEABLE_ONLY), Set.of(NON_TP_MAT));

        ResolvedNeed withOption = new CraftingResolver()
                .resolveOneCraft(FROM_TRADEABLE_ONLY, optionOn,
                        CraftTestFixtures.state(Map.of(TRADEABLE_MAT, 5))).getRoot();
        ResolvedNeed withoutOption = new CraftingResolver()
                .resolveOneCraft(FROM_TRADEABLE_ONLY, optionOff,
                        CraftTestFixtures.state(Map.of(TRADEABLE_MAT, 5))).getRoot();

        assertEquals(withOption.getQtyFromInventory(), withoutOption.getQtyFromInventory());
        assertEquals(withOption.getQtyBought(), withoutOption.getQtyBought());
        assertEquals(withOption.getBuyCostCopper(), withoutOption.getBuyCostCopper());
        assertEquals(withOption.getOpportunityCostCopper(), withoutOption.getOpportunityCostCopper());
        assertEquals(withOption.getBlockedReason(), withoutOption.getBlockedReason());
    }

    @Test
    void anUnclassifiedCalculationIsUnaffectedByTheOptionAtAll() {
        // What every caller that supplies no classification gets: nothing is non-Trading-Post, so
        // even with the option off the owned material is consumed exactly as before.
        PlannerContext ctx = CraftTestFixtures.context(byOutput(FROM_NON_TP), TP,
                CraftTestFixtures.settingsExcludingNonTradeableMaterials(), allowed(FROM_NON_TP));
        PlanState state = CraftTestFixtures.state(Map.of(NON_TP_MAT, 5, TRADEABLE_MAT, 10));

        ResolvedNeed root = new CraftingResolver().resolveOneCraft(FROM_NON_TP, ctx, state).getRoot();

        assertTrue(root.isFullySatisfied());
        assertEquals(4, state.inventory.getOrDefault(NON_TP_MAT, 0));
    }
}
