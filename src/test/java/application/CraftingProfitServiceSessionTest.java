package application;

import craft.CharacterCraftingProfile;
import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STORY-APP-014 acceptance criterion 4, mechanism half: when a calculation is given a captured
 * {@link ProfitInputSession}, every one of its input reads goes through that session's single
 * connection, and none of them opens one of its own.
 *
 * <p>That is what makes the published-generation invariant hold. The session's connection runs one
 * {@code REPEATABLE READ} transaction, so a reader that uses it for all of its reads sees one
 * committed snapshot: it cannot pair a source's part-written refresh rows with the previous run's
 * completion marker, and it cannot mix generations between its own reads. A single read that
 * slipped out to a connection of its own would break both guarantees, which is exactly what this
 * suite fails on.
 *
 * <p>The complementary half — that the database really answers from one snapshot, and that a failed
 * refresh leaves the last-good view intact — needs a real PostgreSQL and lives in
 * {@code application.CachedCraftingProfitPublicationIT}.
 */
class CraftingProfitServiceSessionTest {

    private static final Recipe RECIPE = new Recipe(7, 100, 2, 275, "Artificer", List.of());

    private final Connection pinned = RecordingConnection.create();

    @Test
    void everyInputReadOfATableCalculationUsesTheOneCapturedConnection() throws Exception {
        Reads reads = new Reads();
        service(reads, pinned).reload(DiscChoice.all(), settings(true));

        assertEquals(List.of("recipes:All", "quotes", "items", "characterCrafting", "inventory"),
                reads.onPinnedConnection,
                "all five input reads must run on the captured session's connection");
        assertEquals(List.of(), reads.onOwnConnection,
                "no input read may open a connection of its own: " + reads.onOwnConnection);
    }

    @Test
    void everyInputReadOfOneRecipesDetailUsesTheOneCapturedConnection() throws Exception {
        Reads reads = new Reads();
        service(reads, pinned).resolveDetail(7, DiscChoice.all(), settings(true));

        assertEquals(List.of("recipes:All", "quotes", "items", "characterCrafting", "inventory"),
                reads.onPinnedConnection);
        assertEquals(List.of(), reads.onOwnConnection);
    }

    @Test
    void theCharacterScopedRecipeReadAlsoUsesTheCapturedConnection() throws Exception {
        Reads reads = new Reads();
        service(reads, pinned).reload(DiscChoice.charDiscipline("Artificer", 400, "Aria"), settings(true));

        assertTrue(reads.onPinnedConnection.contains("recipesForCharacter:Aria/Artificer"),
                "the character-scoped recipe read must be pinned too: " + reads.onPinnedConnection);
        assertEquals(List.of(), reads.onOwnConnection);
    }

    @Test
    void aDetachedSessionKeepsThePreviousBehaviorOfEachRepositoryOpeningItsOwnConnection() throws Exception {
        Reads reads = new Reads();
        service(reads, null).reload(DiscChoice.all(), settings(true));

        assertEquals(List.of(), reads.onPinnedConnection);
        assertEquals(List.of("recipes:All", "quotes", "items", "characterCrafting", "inventory"),
                reads.onOwnConnection,
                "a detached session must not change how the existing callers read");
    }

    // ---------- fixtures ----------

    private CraftingProfitService service(Reads reads, Connection connection) {
        ProfitInputSession captured = connection == null
                ? ProfitInputSession.detached()
                : sessionOn(connection);
        return new CraftingProfitService(
                new RecordingRecipeRepository(reads),
                new RecordingInventoryRepository(reads),
                new RecordingTpPriceRepository(reads),
                new RecordingItemRepository(reads),
                new RecordingCharacterRepository(reads),
                new FixedGraphCache(),
                new CraftingPlanner() {
                    @Override
                    public Map<Integer, CraftResult> evaluateAllCoordinated(
                            List<Recipe> allRecipes,
                            Map<Integer, Integer> sellableInventory,
                            Map<Integer, Integer> accountBoundInventory,
                            Map<String, Map<Integer, Integer>> characterBoundInventory,
                            List<CharacterCraftingProfile> roster,
                            Map<Integer, PriceQuote> tp,
                            CraftingSettings settings,
                            Set<Integer> allowedRecipeIds) {
                        return Map.of();
                    }
                },
                captured);
    }

    /**
     * A session over a connection the test supplies. {@link ProfitInputSession#open()} would reach
     * the real database, so the one package-visible way in is reflection on the private constructor
     * - deliberate, because widening the production seam for a test would be the worse trade.
     */
    private static ProfitInputSession sessionOn(Connection connection) {
        try {
            var constructor = ProfitInputSession.class.getDeclaredConstructor(Connection.class);
            constructor.setAccessible(true);
            return constructor.newInstance(connection);
        } catch (ReflectiveOperationException e) {
            return fail("could not build a session over the recording connection", e);
        }
    }

    private static CraftingSettings settings(boolean useOwnMats) {
        return new CraftingSettings(useOwnMats, false, 10_000, false, false, false);
    }

    /** Which reads happened, and whether each used the captured connection or opened its own. */
    private static final class Reads {
        final List<String> onPinnedConnection = new ArrayList<>();
        final List<String> onOwnConnection = new ArrayList<>();

        void pinned(String read) {
            onPinnedConnection.add(read);
        }

        void own(String read) {
            onOwnConnection.add(read);
        }
    }

    private static final class RecordingRecipeRepository extends RecipeRepository {
        private final Reads reads;

        RecordingRecipeRepository(Reads reads) {
            this.reads = reads;
        }

        @Override public List<Recipe> loadRecipes(String discipline) {
            reads.own("recipes:" + discipline);
            return List.of(RECIPE);
        }

        @Override public List<Recipe> loadRecipes(Connection con, String discipline) {
            reads.pinned("recipes:" + discipline);
            return List.of(RECIPE);
        }

        @Override public List<Recipe> loadRecipesForCharacter(String charName, String discipline) {
            reads.own("recipesForCharacter:" + charName + "/" + discipline);
            return List.of(RECIPE);
        }

        @Override public List<Recipe> loadRecipesForCharacter(Connection con, String charName, String discipline) {
            reads.pinned("recipesForCharacter:" + charName + "/" + discipline);
            return List.of(RECIPE);
        }
    }

    private static final class RecordingTpPriceRepository extends TpPriceRepository {
        private final Reads reads;

        RecordingTpPriceRepository(Reads reads) {
            this.reads = reads;
        }

        @Override public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) {
            reads.own("quotes");
            return Map.of();
        }

        @Override public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> itemIds) {
            reads.pinned("quotes");
            return Map.of();
        }
    }

    private static final class RecordingItemRepository extends ItemRepository {
        private final Reads reads;

        RecordingItemRepository(Reads reads) {
            this.reads = reads;
        }

        @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) {
            reads.own("items");
            return Map.of();
        }

        @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> itemIds) {
            reads.pinned("items");
            return Map.of();
        }
    }

    private static final class RecordingCharacterRepository extends CharacterRepository {
        private final Reads reads;

        RecordingCharacterRepository(Reads reads) {
            this.reads = reads;
        }

        @Override public List<DiscRow> loadAllCharacterCrafting() {
            reads.own("characterCrafting");
            return List.of(new DiscRow("Aria", "Artificer", 400, true));
        }

        @Override public List<DiscRow> loadAllCharacterCrafting(Connection con) {
            reads.pinned("characterCrafting");
            return List.of(new DiscRow("Aria", "Artificer", 400, true));
        }
    }

    private static final class RecordingInventoryRepository extends InventoryRepository {
        private final Reads reads;

        RecordingInventoryRepository(Reads reads) {
            this.reads = reads;
        }

        @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> names) {
            reads.own("inventory");
            return new CoordinatedInventory(Map.of(), Map.of(), Map.of());
        }

        @Override public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, Set<String> names) {
            reads.pinned("inventory");
            return new CoordinatedInventory(Map.of(), Map.of(), Map.of());
        }
    }

    /** The shared catalog snapshot, which is not an account or price source and opens nothing here. */
    private static final class FixedGraphCache extends CraftingGraphCache {

        FixedGraphCache() {
            super(new RecipeRepository());
        }

        @Override
        public CraftingGraph load() {
            return new CraftingGraph(List.of(RECIPE));
        }
    }

    /** Identity is all this needs to supply: the repositories above only record that they got it. */
    private static final class RecordingConnection implements java.lang.reflect.InvocationHandler {

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return null;
        }

        static Connection create() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    new RecordingConnection());
        }
    }
}
