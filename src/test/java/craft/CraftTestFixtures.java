package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal hand-built helpers for constructing craft.* domain test fixtures
 * (recipes, ingredients, TP quotes, settings, context, state).
 * Deliberately small - not a generic recipe-graph builder (TEST_STRATEGY.md section 15).
 */
final class CraftTestFixtures {

    private CraftTestFixtures() {
    }

    static RecipeRepository.Ingredient ingredient(int itemId, int count) {
        return new RecipeRepository.Ingredient(itemId, count);
    }

    static RecipeRepository.Recipe recipe(int recipeId, int outputItemId, List<RecipeRepository.Ingredient> ingredients) {
        return recipe(recipeId, outputItemId, 1, ingredients);
    }

    static RecipeRepository.Recipe recipe(int recipeId, int outputItemId, int outputCount, List<RecipeRepository.Ingredient> ingredients) {
        return new RecipeRepository.Recipe(recipeId, outputItemId, outputCount, 0, "Artificer", ingredients);
    }

    static TpPriceRepository.TpQuote quote(Integer buyUnit, Integer sellUnit) {
        return new TpPriceRepository.TpQuote(buyUnit, sellUnit);
    }

    /** No buy or sell listing available on the TP - e.g. a non-tradable item. */
    static TpPriceRepository.TpQuote noQuote() {
        return new TpPriceRepository.TpQuote(null, null);
    }

    /** useOwnMats=true, allowBuying=true, unlimited maxBuyCopper, instant sell/buy, daily craft. */
    static CraftingSettings defaultSettings() {
        return new CraftingSettings(true, true, 0, false, false, false);
    }

    static PlannerContext context(Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput,
                                   Map<Integer, TpPriceRepository.TpQuote> tp,
                                   CraftingSettings settings,
                                   Set<Integer> allowedRecipeIds) {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds);
    }

    static PlanState state(Map<Integer, Integer> baseInventory) {
        return new PlanState(baseInventory);
    }

    static PlanState emptyState() {
        return new PlanState(Map.of());
    }

    /** DOMAIN_SPEC.md section 11.1/DQ-007: bound (no TP opportunity cost) vs. sellable owned quantity. */
    static PlanState stateWithBoundInventory(Map<Integer, Integer> sellableInventory, Map<Integer, Integer> boundInventory) {
        return new PlanState(sellableInventory, boundInventory);
    }

    /** DOMAIN_SPEC.md section 2.2.1: coordinated multi-character planning context. */
    static PlannerContext coordinatedContext(Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput,
                                              Map<Integer, TpPriceRepository.TpQuote> tp,
                                              CraftingSettings settings,
                                              Set<Integer> allowedRecipeIds,
                                              List<CharacterCraftingProfile> roster) {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds, roster);
    }

    /** A candidate character trained in exactly one discipline, at {@code rating}. */
    static CharacterCraftingProfile profile(String name, String discipline, int rating) {
        return new CharacterCraftingProfile(name, Map.of(discipline, rating));
    }

    /**
     * DOMAIN_SPEC.md section 2.2.1: sellable/account-bound pools shared across every character,
     * plus soulbound owned quantity usable only by the exact owning character.
     */
    static PlanState coordinatedState(Map<Integer, Integer> sellableInventory,
                                       Map<Integer, Integer> accountBoundInventory,
                                       Map<String, Map<Integer, Integer>> characterBoundInventory) {
        return new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory);
    }
}
