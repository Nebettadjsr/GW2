package craft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static craft.CraftTestFixtures.quote;
import static craft.CraftTestFixtures.recipe;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #223: exact copper arithmetic must reject only the affected attempt (DOMAIN_SPEC 42). */
class CraftingArithmeticBoundsTest {

    private final CraftingPlanner planner = new CraftingPlanner();

    @ParameterizedTest
    @ValueSource(ints = {1_073_741_823, 1_073_741_824})
    void aPurchaseProductMustFitWithoutWrapping(int unitPrice) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 2)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root), Map.of(), Map.of(),
                Map.of(200, quote(unitPrice, unitPrice)),
                new CraftingSettings(false, true, 0, false, false, false), Set.of(1));

        assertProductBoundary(result, unitPrice, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1_073_741_823, 1_073_741_824})
    void anOwnedOpportunityCostProductMustFitWithoutWrapping(int unitPrice) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 2)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root), Map.of(200, 2), Map.of(),
                Map.of(200, quote(unitPrice, unitPrice)),
                new CraftingSettings(true, false, 0, false, false, false), Set.of(1));

        assertProductBoundary(result, unitPrice, true);
    }

    private static void assertProductBoundary(CraftResult result, int unitPrice, boolean owned) {
        if (unitPrice == 1_073_741_823) {
            assertAll(
                    () -> assertEquals(1, result.craftableCount),
                    () -> assertEquals(BlockedReason.NONE, result.blockedReason),
                    () -> assertEquals(owned ? 0 : 2_147_483_646, result.buyCostCopper),
                    () -> assertEquals(owned ? 2_147_483_646 : 0, result.totalMatsSellValueCopper),
                    () -> assertEquals(-2_147_483_646, result.profitCopper),
                    () -> assertEquals(-2_147_483_646, result.totalProfitCopper));
        } else {
            assertAll(
                    () -> assertEquals(0, result.craftableCount),
                    () -> assertEquals(BlockedReason.VALUE_RANGE, result.blockedReason),
                    () -> assertEquals(0, result.buyCostCopper),
                    () -> assertEquals(0, result.totalMatsSellValueCopper));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void individuallyValidSiblingCostsCannotOverflowTheirSum(boolean owned) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(201, 1)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root),
                owned ? Map.of(200, 1, 201, 1) : Map.of(), Map.of(),
                Map.of(200, quote(1_100_000_000, 1_100_000_000),
                        201, quote(1_100_000_000, 1_100_000_000)),
                new CraftingSettings(owned, !owned, 0, false, false, false), Set.of(1));

        assertEquals(0, result.craftableCount);
        assertEquals(BlockedReason.VALUE_RANGE, result.blockedReason);
        assertEquals(0, result.buyCostCopper);
        assertEquals(0, result.totalMatsSellValueCopper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void profitRetainsTheLastRepresentableCostPrefixIncludingNegativeProfit(boolean owned) {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1)));
        CraftResult result = planner.evaluateOne(root, List.of(root),
                owned ? Map.of(200, 3) : Map.of(), Map.of(),
                Map.of(200, quote(1_000_000_000, 1_000_000_000)),
                new CraftingSettings(owned, !owned, 0, false, false, false), Set.of(1));

        assertAll(
                () -> assertEquals(2, result.craftableCount),
                () -> assertEquals(BlockedReason.VALUE_RANGE, result.blockedReason),
                () -> assertEquals(owned ? 0 : 2_000_000_000, result.buyCostCopper),
                () -> assertEquals(owned ? 2_000_000_000 : 0, result.totalMatsSellValueCopper),
                () -> assertEquals(-1_000_000_000, result.profitCopper),
                () -> assertEquals(-2_000_000_000, result.totalProfitCopper));
    }

    @Test
    void discoveryRejectsAnUnrepresentableOutputRevenueProduct() {
        Recipe root = recipe(1, 100, 2, List.of(ingredient(200, 1)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root), Map.of(), Map.of(),
                Map.of(100, quote(1_500_000_000, 1_500_000_000), 200, quote(1, 1)),
                new CraftingSettings(false, true, 0, false, false, false), Set.of(1));

        assertEquals(0, result.craftableCount);
        assertEquals(BlockedReason.VALUE_RANGE, result.blockedReason);
        assertEquals(0, result.totalSellValueCopper);
        assertEquals(0, result.totalProfitCopper);
    }

    @Test
    void anOutOfRangePurchaseDoesNotHideAnAffordableCraftAlternative() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 2)));
        Recipe intermediate = recipe(2, 200, List.of(ingredient(300, 1)));
        CraftResult result = planner.evaluateSingleCraft(root, List.of(root, intermediate),
                Map.of(), Map.of(), Map.of(200, quote(1_500_000_000, 1_500_000_000), 300, quote(1, 1)),
                new CraftingSettings(false, true, 0, false, false, false), Set.of(1, 2));

        assertEquals(1, result.craftableCount);
        assertEquals(BlockedReason.NONE, result.blockedReason);
        assertEquals(Map.of(300, 2), result.missingToBuy);
        assertEquals(2, result.buyCostCopper);
    }

    @Test
    void profitRetainsTheLastRepresentableRevenuePrefix() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1)));
        Map<Integer, PriceQuote> prices = Map.of(100, quote(20_000_000, 20_000_000),
                200, quote(1, 1));

        CraftResult result = planner.evaluateOne(root, List.of(root), Map.of(), Map.of(), prices,
                new CraftingSettings(false, true, 0, false, false, false), Set.of(1));

        // 107 * 20,000,000 fits; the 108th batch's gross value would exceed signed int copper.
        assertAll(
                () -> assertEquals(107, result.craftableCount),
                () -> assertEquals(BlockedReason.VALUE_RANGE, result.blockedReason),
                () -> assertEquals(2_140_000_000, result.totalSellValueCopper),
                () -> assertEquals(107, result.buyCostCopper),
                () -> assertEquals(Map.of(200, 107), result.missingToBuy),
                () -> assertEquals(Map.of(200, 1), result.missingToBuyOne),
                () -> assertEquals(16_999_999, result.profitCopper),
                () -> assertEquals(1_818_999_893, result.totalProfitCopper));
    }

    @Test
    void aRejectedCombinedOwnedAndPurchasedCostCanStillBeExplained() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(201, 1)));
        Map<Integer, PriceQuote> prices = Map.of(200, quote(1_500_000_000, 1_500_000_000),
                201, quote(1_000_000_000, 1_000_000_000));
        CraftingSettings settings = new CraftingSettings(true, true, 0, false, false, false);

        CraftResult row = planner.evaluateSingleCraft(root, List.of(root), Map.of(200, 1),
                Map.of(), prices, settings, Set.of(1));
        assertEquals(0, row.craftableCount);
        assertEquals(BlockedReason.VALUE_RANGE, row.blockedReason);

        SingleCraftExplanation explanation = assertDoesNotThrow(() -> new SingleCraftExplainer()
                .explainIndividual(root, List.of(root), Map.of(200, 1), Map.of(), prices,
                        settings, Set.of(1)), "a rejected VALUE_RANGE attempt must remain explainable");

        assertTrue(explanation.available());
        assertTrue(explanation.root().blockedReasons().contains(BlockedReason.VALUE_RANGE));
        assertTrue(explanation.root().states().contains(ResolutionState.BLOCKED));
        assertNull(explanation.root().effectiveCostCopper(), "2.5 billion copper has no supported value");
    }

    @Test
    void nestedMaterialLimitSurvivesBuyingDisabledAndStopsLaterSiblings() {
        Recipe root = recipe(1, 100, List.of(ingredient(200, 1), ingredient(300, 1)));
        Recipe oversized = recipe(2, 200, List.of(ingredient(400, 2_501)));
        List<Recipe> recipes = List.of(root, oversized);
        CraftingSettings settings = new CraftingSettings(true, false, 0, false, false, false);

        CraftResult row = planner.evaluateSingleCraft(root, recipes, Map.of(400, 2_501, 300, 1),
                Map.of(), Map.of(), settings, Set.of(1, 2));
        SingleCraftExplanation explanation = new SingleCraftExplainer().explainIndividual(root,
                recipes, Map.of(400, 2_501, 300, 1), Map.of(), Map.of(), settings, Set.of(1, 2));

        assertAll(
                () -> assertEquals(0, row.craftableCount),
                () -> assertEquals(BlockedReason.MATERIAL_LIMIT, row.blockedReason),
                () -> assertTrue(explanation.root().blockedReasons().contains(BlockedReason.MATERIAL_LIMIT)),
                () -> assertEquals(List.of(200), explanation.root().children().stream()
                        .map(CraftTraceNode::itemId).toList(), "the first excess ends the root attempt"));
    }
}
