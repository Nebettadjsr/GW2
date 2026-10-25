package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for DOMAIN_SPEC.md section 22: the planner must choose the
 * acquisition path with the lowest total effective economic cost, not the
 * lowest immediate cash cost.
 */
class CraftingResolverCraftVsBuyTest {

    private static final int FINAL_ITEM_ID = 999;
    private static final int INTERMEDIATE_ITEM_ID = 100;
    private static final int RAW_MATERIAL_ITEM_ID = 200;

    @Test
    void shouldPreferCheaperBuyOverOwnedMaterialCraftPath() {
        // Craft path: consume 1 owned RawMaterial, cash cost 0, opportunity cost 80 -> effective 80.
        // Buy path: buy Intermediate directly for 60 cash, opportunity cost 0 -> effective 60.
        // This is DOMAIN_SPEC.md section 22's own worked example: buy must win despite
        // costing more cash up front, because its effective cost is lower.

        Recipe finalRecipe = new Recipe(
                1, FINAL_ITEM_ID, 1, 0, "Artificer",
                List.of(new Ingredient(INTERMEDIATE_ITEM_ID, 1))
        );
        Recipe intermediateRecipe = new Recipe(
                2, INTERMEDIATE_ITEM_ID, 1, 0, "Artificer",
                List.of(new Ingredient(RAW_MATERIAL_ITEM_ID, 1))
        );

        Map<Integer, List<Recipe>> recipesByOutput = Map.of(
                FINAL_ITEM_ID, List.of(finalRecipe),
                INTERMEDIATE_ITEM_ID, List.of(intermediateRecipe)
        );

        Map<Integer, PriceQuote> tp = Map.of(
                // Intermediate: buyable directly for 60 cash (instant buy -> sellUnit, DOMAIN_SPEC.md section 20).
                INTERMEDIATE_ITEM_ID, new PriceQuote(null, 60),
                // RawMaterial: cheap to buy fresh (5) -- this keeps the resolver's internal
                // craft-viability pre-filter (CraftingResolver.estimateDirectCraftFloor) from
                // skipping the craft path entirely -- but worth 80 if sold instead of consumed
                // (instant sell -> buyUnit). That 80 is the opportunity cost of using the owned copy.
                RAW_MATERIAL_ITEM_ID, new PriceQuote(80, 5)
        );

        CraftingSettings settings = new CraftingSettings(
                true,   // useOwnMats
                true,   // allowBuying
                0,      // maxBuyCopper (0 = unlimited)
                false,  // listingSell (instant sell)
                false,  // listingBuy (instant buy)
                false   // allowDailyCrafts
        );

        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, Set.of(1, 2));
        PlanState state = new PlanState(Map.of(RAW_MATERIAL_ITEM_ID, 1));

        ResolveResult result = new CraftingResolver().resolveOneCraft(finalRecipe, ctx, state);
        ResolvedNeed intermediateNeed = result.getRoot().getChildren().get(0);

        assertEquals(AcquisitionMode.BUY, intermediateNeed.getMode());
        assertEquals(1, intermediateNeed.getQtyBought());
        assertEquals(0, intermediateNeed.getQtyCrafted());
        assertEquals(60, intermediateNeed.getBuyCostCopper());
        assertEquals(0, intermediateNeed.getOpportunityCostCopper());
        assertEquals(60, intermediateNeed.getEffectiveCostCopper());
    }
}
