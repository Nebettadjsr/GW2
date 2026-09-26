package application;

import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftTraceNode;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.MaterialTradeability;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;
import repo.tp.TpTradeableItemRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer flow for the non-Trading-Post material option (DOMAIN_SPEC.md §2.1.1, UD-009 /
 * UD-010, STORY-DOM-021): that the classification is loaded from its own adapter only when the option
 * restricts anything, and that {@link CraftingProfitService#reload} and
 * {@link CraftingProfitService#resolveDetail} apply the same rule to the same facts.
 *
 * <p>Fake adapters, the real planner and the real explainer, as in {@link CraftingProfitServiceTest}:
 * this suite proves the wiring, not the domain rule ({@code craft.CraftingResolverNonTradeableMaterialTest}
 * owns that).
 */
class CraftingProfitNonTradeableMaterialFlowTest {

    private static final int OUTPUT = 100;
    private static final int TRADEABLE_MAT = 200;
    private static final int NON_TP_MAT = 300;

    private static final Recipe RECIPE = new Recipe(
            1, OUTPUT, 1, 0, "Artificer",
            List.of(new Ingredient(TRADEABLE_MAT, 2), new Ingredient(NON_TP_MAT, 1)));

    private static final Map<Integer, PriceQuote> QUOTES = Map.of(
            OUTPUT, new PriceQuote(900, 1000),
            TRADEABLE_MAT, new PriceQuote(10, 12),
            NON_TP_MAT, new PriceQuote(null, null));

    private static CraftingSettings settings(boolean allowNonTradeableMaterials) {
        return new CraftingSettings(true, true, 0, false, false, false, allowNonTradeableMaterials);
    }

    @Test
    void enabledOptionLoadsNoClassificationAndUsesTheOwnedNonTradeableMaterial() throws SQLException {
        var fakes = new Fakes();
        CraftingProfitService service = fakes.service();

        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), settings(true));

        assertTrue(fakes.tradeabilityQueries.isEmpty(),
                "with the option enabled nothing is classified, so no classification is loaded");
        CraftResult result = data.resultsByRecipeId().get(RECIPE.recipeId);
        assertNotNull(result);

        // All five owned units of the material were usable, one per craft. The sixth craft would have
        // to acquire it externally, which the enabled option does not invent: it stops at the
        // unchanged §21 missing-price reason rather than at the new restriction.
        assertEquals(5, result.craftableCount);
        assertEquals(BlockedReason.PRICE_UNAVAILABLE, result.blockedReason);
    }

    @Test
    void disabledOptionClassifiesTheCalculationsItemsAndBlocksThePathThatConsumesOne() throws SQLException {
        var fakes = new Fakes();
        CraftingProfitService service = fakes.service();

        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), settings(false));

        assertEquals(1, fakes.tradeabilityQueries.size());
        assertEquals(Set.of(OUTPUT, TRADEABLE_MAT, NON_TP_MAT), fakes.tradeabilityQueries.get(0),
                "the classification is asked about exactly this calculation's items");

        CraftResult result = data.resultsByRecipeId().get(RECIPE.recipeId);
        assertNotNull(result);
        assertEquals(0, result.craftableCount);
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, result.blockedReason);
    }

    @Test
    void theFreshDetailAppliesTheSameRuleAndNamesTheRestrictedMaterial() throws SQLException {
        var fakes = new Fakes();
        CraftingProfitService service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(RECIPE.recipeId, DiscChoice.all(), settings(false));

        assertEquals(1, fakes.tradeabilityQueries.size(),
                "the detail operation captures its own classification, like every other input");
        assertNotNull(detail.row());
        assertEquals(BlockedReason.NON_TRADEABLE_MATERIAL, detail.row().blockedReason);

        CraftTraceNode root = detail.explanation().root();
        assertNotNull(root, "a rejected path is still explained");
        assertTrue(root.blockedReasons().contains(BlockedReason.NON_TRADEABLE_MATERIAL));

        CraftTraceNode restricted = null;
        for (CraftTraceNode child : root.children()) {
            if (child.itemId() == NON_TP_MAT) restricted = child;
        }
        assertNotNull(restricted, "the restriction is reported at the material it applies to");
        assertEquals(List.of(BlockedReason.NON_TRADEABLE_MATERIAL), restricted.blockedReasons());
        assertEquals(0, restricted.inventoryQuantity());
        assertEquals(0, restricted.boughtQuantity());
    }

    @Test
    void theEnabledDetailIsUnrestrictedExactlyAsItsTableRowIs() throws SQLException {
        var fakes = new Fakes();
        CraftingProfitService service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(RECIPE.recipeId, DiscChoice.all(), settings(true));

        assertTrue(fakes.tradeabilityQueries.isEmpty());
        assertTrue(detail.explanation().available());

        // The explained craft used the owned non-Trading-Post material, and no restriction applies.
        CraftTraceNode root = detail.explanation().root();
        assertTrue(root.blockedReasons().isEmpty());

        CraftTraceNode used = null;
        for (CraftTraceNode child : root.children()) {
            if (child.itemId() == NON_TP_MAT) used = child;
        }
        assertNotNull(used);
        assertEquals(1, used.inventoryQuantity());
        assertTrue(used.blockedReasons().isEmpty());
    }

    // ---------- Fake/in-memory adapters (TARGET_ARCHITECTURE.md §25) ----------

    private static class Fakes {
        /** One entry per classification load, holding the item ids it was asked about. */
        final List<Set<Integer>> tradeabilityQueries = new ArrayList<>();

        CraftingProfitService service() {
            return new CraftingProfitService(
                    new FakeRecipeRepository(), new FakeInventoryRepository(),
                    new FakeTpPriceRepository(), new FakeTradeableItemRepository(this),
                    new FakeItemRepository(), new FakeCharacterRepository(),
                    new FakeCraftingGraphCache(), new CraftingPlanner());
        }
    }

    private static class FakeCraftingGraphCache extends CraftingGraphCache {
        FakeCraftingGraphCache() {
            super(null);
        }

        @Override
        public CraftingGraph load() {
            return new CraftingGraph(List.of(RECIPE));
        }
    }

    private static class FakeRecipeRepository extends RecipeRepository {
        @Override
        public List<Recipe> loadRecipes(String discipline) {
            return List.of(RECIPE);
        }

        @Override
        public List<Recipe> loadRecipesForCharacter(String charName, String discipline) {
            return List.of(RECIPE);
        }
    }

    /** Both materials are owned, so nothing depends on buying them. */
    private static class FakeInventoryRepository extends InventoryRepository {
        @Override
        public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames) {
            return new CoordinatedInventory(Map.of(TRADEABLE_MAT, 20, NON_TP_MAT, 5), Map.of(), Map.of());
        }
    }

    private static class FakeTpPriceRepository extends TpPriceRepository {
        @Override
        public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) {
            return QUOTES;
        }
    }

    /**
     * Classifies exactly {@link #NON_TP_MAT} as non-Trading-Post - independently of the quotes above,
     * which give the tradeable material a price and this one none.
     */
    private static class FakeTradeableItemRepository extends TpTradeableItemRepository {
        private final Fakes fakes;

        FakeTradeableItemRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public MaterialTradeability loadTradeability(Set<Integer> itemIds) {
            fakes.tradeabilityQueries.add(Set.copyOf(itemIds));
            return MaterialTradeability.ofNonTradeableItems(Set.of(NON_TP_MAT));
        }
    }

    private static class FakeItemRepository extends ItemRepository {
        @Override
        public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) {
            return Map.of(
                    OUTPUT, new ItemInfo(OUTPUT, "Widget", null, null),
                    NON_TP_MAT, new ItemInfo(NON_TP_MAT, "Account Bound Scrap", null, null));
        }
    }

    private static class FakeCharacterRepository extends CharacterRepository {
        @Override
        public List<DiscRow> loadAllCharacterCrafting() {
            return List.of(new DiscRow("Aria", "Artificer", 400, true));
        }
    }
}
