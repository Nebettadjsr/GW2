package application;

import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-015 criterion 7: the real planner, fed one account's scoped materials, recipe knowledge
 * and roster, gives the result a hand calculation gives for that account - and a second account that
 * shares the same item/recipe identifiers gets its own, unchanged by the first.
 *
 * <p>Fixture, specified here and not derived from the code under test: recipe 7 makes 1 item 100
 * from 2 of item 200 (rating 100, Artificer); item 200 costs 10 copper to buy. Account A knows
 * recipe 7, owns 5 of item 200 and has an Artificer at 400, so it can craft 2 without buying
 * anything. Account B owns 1 of item 200 and has the same discipline but has not learned the recipe.
 */
class CraftingProfitServiceScopedInputsTest {

    private static final AccountScope A = AccountScope.of(UUID.fromString("10000000-0000-0000-0000-00000000000a"));
    private static final AccountScope B = AccountScope.of(UUID.fromString("20000000-0000-0000-0000-00000000000b"));
    private static final Recipe RECIPE = new Recipe(7, 100, 1, 100, "Artificer", List.of(new Ingredient(200, 2)));

    @Test
    void accountAOwnedMaterialsAndKnownRecipeYieldTheHandCalculatedCraft() throws Exception {
        var data = service(A).reload(DiscChoice.all(), settings());

        assertEquals(List.of(7), data.visibleRecipes().stream().map(r -> r.recipeId).toList());
        CraftResult r = data.resultsByRecipeId().get(7);
        assertEquals(2, r.craftableCount, "5 owned / 2 per craft");
        assertTrue(r.missingToBuy.isEmpty(), "nothing needs buying: " + r.missingToBuy);
        assertEquals(0, r.buyCostCopper);
    }

    @Test
    void accountBSharesIdentifiersButNeitherKnowsTheRecipeNorOwnsAccountAsMaterials() throws Exception {
        var data = service(B).reload(DiscChoice.all(), settings());

        assertTrue(data.visibleRecipes().isEmpty(), "B has not learned recipe 7");
        CraftResult r = data.resultsByRecipeId().get(7);
        assertTrue(r == null || r.craftableCount == 0, "B must not be credited with A's recipe or materials");
        assertFalse(data.resultsByRecipeId().values().stream().anyMatch(x -> x.craftableCount == 2));
    }

    // ---------- fixtures ----------

    private static CraftingProfitService service(AccountScope scope) {
        return new CraftingProfitService(new ScopedRecipes(), new ScopedInventory(), new Quotes(), new Items(),
                new ScopedCharacters(), new FixedGraphCache(), new CraftingPlanner(), scopedSession(scope));
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }

    private static ProfitInputSession scopedSession(AccountScope scope) {
        Connection con = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> null);
        try {
            var c = ProfitInputSession.class.getDeclaredConstructor(Connection.class, AccountScope.class);
            c.setAccessible(true);
            return c.newInstance(con, scope);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class ScopedRecipes extends RecipeRepository {
        @Override public List<Recipe> loadRecipes(Connection con, AccountScope scope, String discipline) {
            return scope.equals(A) ? List.of(RECIPE) : List.of();
        }
        @Override public List<Recipe> loadRecipes(Connection con, String discipline) {
            throw new AssertionError("unscoped recipe read");
        }
    }

    private static final class ScopedInventory extends InventoryRepository {
        @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, AccountScope scope,
                                                                              Set<String> names) {
            return new CoordinatedInventory(Map.of(200, scope.equals(A) ? 5 : 1), Map.of(), Map.of());
        }
        @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, Set<String> names) {
            throw new AssertionError("unscoped inventory read");
        }
    }

    private static final class ScopedCharacters extends CharacterRepository {
        @Override public List<DiscRow> loadAllCharacterCrafting(Connection con, AccountScope scope) {
            return List.of(new DiscRow(scope.equals(A) ? "Aria" : "Bran", "Artificer", 400, true));
        }
        @Override public List<DiscRow> loadAllCharacterCrafting(Connection con) {
            throw new AssertionError("unscoped roster read");
        }
    }

    /** Shared global quote: both accounts see the same price. */
    private static final class Quotes extends TpPriceRepository {
        @Override public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> ids) {
            return Map.of(200, new PriceQuote(10, 12), 100, new PriceQuote(50, 60));
        }
    }

    private static final class Items extends ItemRepository {
        @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> ids) {
            return Map.of();
        }
    }

    private static final class FixedGraphCache extends CraftingGraphCache {
        FixedGraphCache() {
            super(new RecipeRepository());
        }
        @Override public CraftingGraph load() {
            return new CraftingGraph(List.of(RECIPE));
        }
    }
}
