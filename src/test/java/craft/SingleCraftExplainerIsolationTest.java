package craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TARGET_ARCHITECTURE.md section 13.2: an explanation works from the inputs it was handed, must not
 * mutate them, and must not carry state into the next explanation.
 */
class SingleCraftExplainerIsolationTest {

    private static final int FINAL_ITEM = 999;
    private static final int RAW_ITEM = 200;
    private static final int DAILY_ITEM = 43772;

    private static final Recipe ROOT = recipe(1, FINAL_ITEM, List.of(ingredient(RAW_ITEM, 2)));

    private final SingleCraftExplainer explainer = new SingleCraftExplainer();

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying, int maxBuyCopper) {
        return new CraftingSettings(useOwnMats, allowBuying, maxBuyCopper, false, false, false);
    }

    @Test
    void shouldNotMutateTheSuppliedInitialState() {
        Map<Integer, Integer> sellable = new HashMap<>(Map.of(RAW_ITEM, 5));
        Map<Integer, Integer> bound = new HashMap<>(Map.of(RAW_ITEM, 1));
        Map<String, Map<Integer, Integer>> characterBound =
                new HashMap<>(Map.of("Ann", new HashMap<>(Map.of(RAW_ITEM, 3))));

        explainer.explainCoordinated(
                new Recipe(1, FINAL_ITEM, 1, 0, "Artificer", List.of(ingredient(RAW_ITEM, 2))),
                List.of(ROOT),
                sellable,
                bound,
                characterBound,
                List.of(CraftTestFixtures.profile("Ann", "Artificer", 0)),
                Map.of(RAW_ITEM, quote(6, 9)),
                settings(true, true, 1000),
                Set.of(1));

        assertEquals(Map.of(RAW_ITEM, 5), sellable);
        assertEquals(Map.of(RAW_ITEM, 1), bound);
        assertEquals(Map.of("Ann", Map.of(RAW_ITEM, 3)), characterBound);
    }

    @Test
    void shouldProduceIdenticalExplanationsForRepeatedIdenticalRequests() {
        SingleCraftExplanation first = explainOwnMats(Map.of(RAW_ITEM, 5));
        SingleCraftExplanation second = explainOwnMats(Map.of(RAW_ITEM, 5));

        assertEquals(first, second);
        assertEquals(Integer.valueOf(12), first.root().effectiveCostCopper());
    }

    @Test
    void shouldNotCarryTheDailyAllowanceIntoTheNextExplanation() {
        Recipe root = recipe(1, FINAL_ITEM, List.of(ingredient(DAILY_ITEM, 1)));
        Recipe daily = recipe(2, DAILY_ITEM, List.of(ingredient(RAW_ITEM, 1)));

        SingleCraftExplanation first = explainer.explainIndividual(
                root, List.of(root, daily), Map.of(RAW_ITEM, 5), Map.of(),
                Map.of(RAW_ITEM, quote(3, 4)), settings(true, false, 0), Set.of(1, 2));
        SingleCraftExplanation second = explainer.explainIndividual(
                root, List.of(root, daily), Map.of(RAW_ITEM, 5), Map.of(),
                Map.of(RAW_ITEM, quote(3, 4)), settings(true, false, 0), Set.of(1, 2));

        assertEquals(1, first.root().children().get(0).craftCount());
        assertEquals(first, second, "the second explanation must start from a fresh daily state");
    }

    @Test
    void shouldNotLetABlockedExplanationContaminateTheNextOne() {
        SingleCraftExplanation blocked = explainOwnMats(Map.of(RAW_ITEM, 1));
        assertEquals(List.of(BlockedReason.BUYING_DISABLED), blocked.root().blockedReasons());
        assertEquals(1, blocked.root().children().get(0).missingQuantity());

        SingleCraftExplanation succeeded = explainOwnMats(Map.of(RAW_ITEM, 5));
        assertEquals(List.of(), succeeded.root().blockedReasons());
        assertEquals(2, succeeded.root().children().get(0).inventoryQuantity());
        assertEquals(Integer.valueOf(12), succeeded.root().effectiveCostCopper());
    }

    @Test
    void shouldKeepConsecutiveExplanationsWithDifferentSettingsAndScopesIndependent() {
        SingleCraftExplanation ownMats = explainOwnMats(Map.of(RAW_ITEM, 5));
        assertEquals(2, ownMats.root().children().get(0).inventoryQuantity());
        assertEquals(Integer.valueOf(0), ownMats.root().cashCostCopper());

        // Same explainer, different settings: buying only, no owned materials.
        SingleCraftExplanation buying = explainer.explainIndividual(
                ROOT, List.of(ROOT), Map.of(RAW_ITEM, 5), Map.of(),
                Map.of(RAW_ITEM, quote(6, 9)), settings(false, true, 0), Set.of(1));

        assertEquals(0, buying.root().children().get(0).inventoryQuantity());
        assertEquals(2, buying.root().children().get(0).boughtQuantity());
        assertEquals(Integer.valueOf(18), buying.root().cashCostCopper());

        // Same explainer, a coordinated scope whose roster cannot perform the recipe.
        SingleCraftExplanation coordinated = explainer.explainCoordinated(
                new Recipe(1, FINAL_ITEM, 1, 400, "Artificer", List.of(ingredient(RAW_ITEM, 2))),
                List.of(ROOT), Map.of(RAW_ITEM, 5), Map.of(), Map.of(),
                List.of(CraftTestFixtures.profile("Ann", "Tailor", 400)),
                Map.of(RAW_ITEM, quote(6, 9)), settings(true, false, 0), Set.of(1));

        assertTrue(coordinated.root().blockedReasons().contains(BlockedReason.RECIPE_NOT_ALLOWED));

        // And back to the first request, which must be unaffected by either of the two above.
        assertEquals(ownMats, explainOwnMats(Map.of(RAW_ITEM, 5)));
    }

    private SingleCraftExplanation explainOwnMats(Map<Integer, Integer> sellableInventory) {
        return explainer.explainIndividual(
                ROOT,
                List.of(ROOT),
                sellableInventory,
                Map.of(),
                Map.of(RAW_ITEM, quote(6, 9)),
                settings(true, false, 0),
                Set.of(1));
    }
}
