package application;

import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An account with NO trained roster still owns Bank/Materials stock a Forge recipe may use (a Forge step needs no
 * crafting character). Hand fixture: bundled Abyssal Scepter (wiki forge id 3105801, output 31058) consumes
 * 20852x1, 19976x100, 12976x250, 19721x250; the account owns exactly one set in shared stock and has no characters.
 */
class CraftingProfitServiceForgeEmptyRosterTest {

    private static final AccountScope A = AccountScope.of(UUID.fromString("10000000-0000-0000-0000-00000000000a"));
    private static final int SCEPTER_RECIPE = -3105801;

    @Test
    void staleForgeOptInNeverReEnablesForgeInProfit() throws Exception {
        // Issue #378 product change: Profit is ordinary crafting only; a stale true flag is dropped at the service.
        var data = service().reload(DiscChoice.all(), settings(true));
        assertNull(data.resultsByRecipeId().get(SCEPTER_RECIPE));
        assertTrue(data.visibleRecipes().isEmpty());
        assertTrue(data.allRecipes().stream().noneMatch(Recipe::isMysticForge));
    }

    @Test
    void disabledForgeKeepsOrdinaryBehaviourWithAnEmptyRoster() throws Exception {
        var data = service().reload(DiscChoice.all(), settings(false));
        assertTrue(data.resultsByRecipeId().get(SCEPTER_RECIPE) == null);
        assertTrue(data.visibleRecipes().isEmpty());
    }

    private static CraftingSettings settings(boolean forge) {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(forge);
    }

    private static CraftingProfitService service() {
        return new CraftingProfitService(new RecipeRepository() {
            @Override public List<Recipe> loadRecipes(Connection con, AccountScope scope, String discipline) {
                return List.of();
            }
        }, new InventoryRepository() {
            @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, AccountScope scope,
                                                                                  Set<String> names) {
                return new CoordinatedInventory(Map.of(20852, 1, 19976, 100, 12976, 250, 19721, 250), Map.of(), Map.of());
            }

            // Enabled Forge asks for every account character's carried owner stock through this overload.
            @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, AccountScope scope,
                                                                                  Set<String> names, boolean keepAllCarriedOwners) {
                return loadOwnedInventoryForCharacters(con, scope, names);
            }
        }, new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Connection con, Set<Integer> ids) {
                return Map.of(31058, new PriceQuote(1000, 1100));
            }
        }, new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> ids) { return Map.of(); }
        }, new CharacterRepository() {
            @Override public List<DiscRow> loadAllCharacterCrafting(Connection con, AccountScope scope) { return List.of(); }
        }, new CraftingGraphCache(new RecipeRepository()) {
            @Override public CraftingGraph load() { return new CraftingGraph(List.of()); }
            @Override public CraftingGraph loadForSnapshot(Connection con) { return load(); }
        }, new CraftingPlanner(), session());
    }

    private static ProfitInputSession session() {
        Connection con = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> null);
        try {
            var c = ProfitInputSession.class.getDeclaredConstructor(Connection.class, AccountScope.class);
            c.setAccessible(true);
            return c.newInstance(con, A);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
