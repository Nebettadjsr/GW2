package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.coordinatedContext;
import static craft.CraftTestFixtures.coordinatedState;
import static craft.CraftTestFixtures.defaultSettings;
import static craft.CraftTestFixtures.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOMAIN_SPEC.md section 2.2.1 / STORY-DOM-014: coordinated account-wide crafting across every
 * eligible synced character. Different steps may be assigned to different eligible characters
 * (per-step recipe discipline/rating eligibility), transferable intermediates may pass between
 * them, but soulbound ingredients remain restricted to whichever character actually owns them -
 * two characters each holding one soulbound copy can never jointly satisfy a step needing two.
 */
class CraftingResolverCoordinatedCharactersTest {

    private static final int FINAL_ITEM_ID = 100;
    private static final int INTERMEDIATE_ITEM_ID = 200;
    private static final int RAW_ITEM_ID = 300;
    private static final int SOULBOUND_ITEM_ID = 600;

    @Test
    void ownedOutputDoesNotBypassMissingCharacterEligibility() {
        var recipe = new Recipe(1, FINAL_ITEM_ID, 1, 400, "Chef",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));
        var ctx = coordinatedContext(Map.of(FINAL_ITEM_ID, List.of(recipe)), Map.of(),
                defaultSettings(), Set.of(1), List.of());
        var result = new CraftingResolver().resolveOneCraft(recipe, ctx,
                coordinatedState(Map.of(FINAL_ITEM_ID, 1), Map.of(), Map.of()));
        assertEquals(BlockedReason.RECIPE_NOT_ALLOWED, result.getRoot().getBlockedReason());
        assertFalse(result.getRoot().isFullySatisfied());
    }

    @Test
    void ineligiblePreferredRecipeDoesNotHideEligibleAlternative() {
        var unavailable = new Recipe(1, INTERMEDIATE_ITEM_ID, 1, 400,
                "Weaponsmith", List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));
        var available = new Recipe(2, INTERMEDIATE_ITEM_ID, 1, 0,
                "Armorsmith", List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));
        var ctx = coordinatedContext(Map.of(INTERMEDIATE_ITEM_ID, List.of(unavailable, available)),
                Map.of(), defaultSettings(), Set.of(1, 2),
                List.of(profile("Alice", "Armorsmith", 50)));
        var result = new CraftingResolver().resolveNeed(INTERMEDIATE_ITEM_ID, 1, ctx,
                coordinatedState(Map.of(RAW_ITEM_ID, 1), Map.of(), Map.of()), false);
        assertTrue(result.isFullySatisfied());
    }

    @Test
    void intermediateProducedByOneEligibleCharacter_finalStepByAnotherEligibleCharacter() {
        // Final recipe needs a Weaponsmith; its intermediate needs an Armorsmith. No single
        // character in the roster has both discipline - this can only succeed by coordinating.
        Recipe finalRecipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Weaponsmith",
                List.of(CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1)));
        Recipe intermediateRecipe = new Recipe(
                2, INTERMEDIATE_ITEM_ID, 1, 0, "Armorsmith",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                FINAL_ITEM_ID, List.of(finalRecipe),
                INTERMEDIATE_ITEM_ID, List.of(intermediateRecipe));

        Map<Integer, PriceQuote> tp = Map.of(RAW_ITEM_ID, CraftTestFixtures.quote(null, 100));

        List<CharacterCraftingProfile> roster = List.of(
                profile("Armorsmith Alice", "Armorsmith", 50),
                profile("Weaponsmith Carl", "Weaponsmith", 50));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1, 2), roster);
        PlanState state = coordinatedState(Map.of(), Map.of(), Map.of());

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);

        assertTrue(result.getRoot().isFullySatisfied(),
                "Alice can craft the intermediate and Carl can assemble the final item, "
                        + "even though neither alone has both disciplines");
        ResolvedNeed intermediateNeed = result.getRoot().getChildren().get(0);
        assertEquals(AcquisitionMode.CRAFT, intermediateNeed.getMode());
    }

    @Test
    void twoTransferableIntermediatesProducedByDifferentCharacters_feedAThirdCharactersFinalStep() {
        // This is UD-004's three-character case: neither intermediate needs to remain with
        // its crafter, but every recipe step still has its own discipline assignment.
        Recipe finalRecipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Weaponsmith", List.of(
                CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1),
                CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID + 1, 1)));
        Recipe firstIntermediate = new Recipe(
                2, INTERMEDIATE_ITEM_ID, 1, 0, "Armorsmith",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));
        Recipe secondIntermediate = new Recipe(
                3, INTERMEDIATE_ITEM_ID + 1, 1, 0, "Huntsman",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID + 1, 1)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                FINAL_ITEM_ID, List.of(finalRecipe),
                INTERMEDIATE_ITEM_ID, List.of(firstIntermediate),
                INTERMEDIATE_ITEM_ID + 1, List.of(secondIntermediate));
        Map<Integer, PriceQuote> tp = Map.of(
                RAW_ITEM_ID, CraftTestFixtures.quote(null, 100),
                RAW_ITEM_ID + 1, CraftTestFixtures.quote(null, 100));
        List<CharacterCraftingProfile> roster = List.of(
                profile("Armorsmith Alice", "Armorsmith", 50),
                profile("Huntsman Bea", "Huntsman", 50),
                profile("Weaponsmith Carl", "Weaponsmith", 50));

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe,
                coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1, 2, 3), roster),
                coordinatedState(Map.of(), Map.of(), Map.of()));

        assertTrue(result.getRoot().isFullySatisfied());
        assertEquals(2, result.getRoot().getChildren().size());
        assertTrue(result.getRoot().getChildren().stream()
                .allMatch(child -> child.getMode() == AcquisitionMode.CRAFT));
    }

    @Test
    void transferableIntermediateAndSharedInventoryAreConsumedOncePerCraft() {
        Recipe finalRecipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Weaponsmith",
                List.of(CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1)));
        Recipe intermediateRecipe = new Recipe(
                2, INTERMEDIATE_ITEM_ID, 1, 0, "Armorsmith",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));
        List<Recipe> recipes = List.of(finalRecipe, intermediateRecipe);
        List<CharacterCraftingProfile> roster = List.of(
                profile("Armorsmith Alice", "Armorsmith", 50),
                profile("Weaponsmith Carl", "Weaponsmith", 50));

        CraftResult result = new CraftingPlanner().evaluateAllCoordinated(
                recipes, Map.of(RAW_ITEM_ID, 1), Map.of(), Map.of(), roster,
                Map.of(), defaultSettings(), Set.of(1, 2)).get(1);

        assertEquals(1, result.craftableCount,
                "One shared raw material produces one transferable intermediate and one final craft; "
                        + "it must not be reused for a second final craft");
    }

    @Test
    void noRosterCharacterHasTheRequiredDiscipline_stepIsBlockedRecipeNotAllowed() {
        Recipe finalRecipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Weaponsmith",
                List.of(CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1)));
        Recipe intermediateRecipe = new Recipe(
                2, INTERMEDIATE_ITEM_ID, 1, 0, "Armorsmith",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                FINAL_ITEM_ID, List.of(finalRecipe),
                INTERMEDIATE_ITEM_ID, List.of(intermediateRecipe));

        Map<Integer, PriceQuote> tp = Map.of(RAW_ITEM_ID, CraftTestFixtures.quote(null, 100));

        // Only a Weaponsmith is synced - nobody can perform the Armorsmith intermediate step.
        List<CharacterCraftingProfile> roster = List.of(profile("Weaponsmith Carl", "Weaponsmith", 50));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1, 2), roster);
        PlanState state = coordinatedState(Map.of(), Map.of(), Map.of());

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);

        assertFalse(result.getRoot().isFullySatisfied());
        assertEquals(BlockedReason.RECIPE_NOT_ALLOWED, result.getRoot().getBlockedReason());
    }

    @Test
    void ratingBelowRecipeMinimum_excludesThatCharacterFromEligibility() {
        Recipe recipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 400, "Armorsmith",
                List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(FINAL_ITEM_ID, List.of(recipe));
        Map<Integer, PriceQuote> tp = Map.of(RAW_ITEM_ID, CraftTestFixtures.quote(null, 100));

        // Below the recipe's min_rating of 400 - not eligible despite having the discipline.
        List<CharacterCraftingProfile> roster = List.of(profile("Novice Nora", "Armorsmith", 50));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1), roster);
        PlanState state = coordinatedState(Map.of(), Map.of(), Map.of());

        ResolveResult result = new CraftingResolver().resolveOneCraft(recipe, ctx, state);

        assertFalse(result.getRoot().isFullySatisfied());
        assertEquals(BlockedReason.RECIPE_NOT_ALLOWED, result.getRoot().getBlockedReason());
    }

    @Test
    void twoCharactersEachHoldingOneSoulboundUnit_cannotJointlySatisfyStepNeedingTwo() {
        // DOMAIN_SPEC.md section 2.2.1's own example: neither character alone owns the required
        // 2 units, and their separate soulbound copies must never be pooled together.
        Recipe recipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Tailor",
                List.of(CraftTestFixtures.ingredient(SOULBOUND_ITEM_ID, 2)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(FINAL_ITEM_ID, List.of(recipe));
        // No Trading Post quote at all - soulbound items cannot be sold or bought either way.
        Map<Integer, PriceQuote> tp = Map.of();

        List<CharacterCraftingProfile> roster = List.of(
                profile("Tailor Amy", "Tailor", 10),
                profile("Tailor Bea", "Tailor", 10));

        Map<String, Map<Integer, Integer>> characterBound = Map.of(
                "Tailor Amy", Map.of(SOULBOUND_ITEM_ID, 1),
                "Tailor Bea", Map.of(SOULBOUND_ITEM_ID, 1));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1), roster);
        PlanState state = coordinatedState(Map.of(), Map.of(), characterBound);

        ResolveResult result = new CraftingResolver().resolveOneCraft(recipe, ctx, state);

        assertFalse(result.getRoot().isFullySatisfied(),
                "1 owned by Amy + 1 owned by Bea must never combine to satisfy a single "
                        + "character's requirement of 2");
    }

    @Test
    void oneCharacterOwningBothSoulboundUnits_canSatisfyTheStepAlone() {
        Recipe recipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Tailor",
                List.of(CraftTestFixtures.ingredient(SOULBOUND_ITEM_ID, 2)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(FINAL_ITEM_ID, List.of(recipe));
        Map<Integer, PriceQuote> tp = Map.of(
                SOULBOUND_ITEM_ID, CraftTestFixtures.quote(200, 150));

        List<CharacterCraftingProfile> roster = List.of(profile("Tailor Amy", "Tailor", 10));
        Map<String, Map<Integer, Integer>> characterBound = Map.of(
                "Tailor Amy", Map.of(SOULBOUND_ITEM_ID, 2));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1), roster);
        PlanState state = coordinatedState(Map.of(), Map.of(), characterBound);

        ResolveResult result = new CraftingResolver().resolveOneCraft(recipe, ctx, state);

        assertTrue(result.getRoot().isFullySatisfied());
        assertEquals(0, result.getRoot().getOpportunityCostCopper(),
                "soulbound consumption never carries a Trading Post opportunity cost");
        assertEquals(0, result.getRoot().getBuyCostCopper());
    }

    @Test
    void emptyRoster_blocksEveryRecipeAsRecipeNotAllowed() {
        Recipe recipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Tailor", List.of(CraftTestFixtures.ingredient(RAW_ITEM_ID, 1)));

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(FINAL_ITEM_ID, List.of(recipe));
        Map<Integer, PriceQuote> tp = Map.of(RAW_ITEM_ID, CraftTestFixtures.quote(null, 100));

        PlannerContext ctx = coordinatedContext(recipesByOutput, tp, defaultSettings(), Set.of(1), List.of());
        PlanState state = coordinatedState(Map.of(), Map.of(), Map.of());

        ResolveResult result = new CraftingResolver().resolveOneCraft(recipe, ctx, state);

        assertFalse(result.getRoot().isFullySatisfied());
        assertEquals(BlockedReason.RECIPE_NOT_ALLOWED, result.getRoot().getBlockedReason());
    }
}
