package repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import craft.CraftingGraph;
import craft.Ingredient;
import craft.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-COV-003: current read/parse and rebuild-failure behaviour of {@link CraftingGraphCache}
 * with test-owned temporary cache files and deterministic in-memory repositories (no database).
 *
 * <p>These tests record the behaviour KP-16 (docs/KNOWN_PROBLEMS.md) describes; they do not change the
 * cache policy. Where today's behaviour is the known weakness - a stored cache is trusted without a
 * freshness check, and a corrupt file fails every load instead of being rebuilt - the test says so
 * and asserts what actually happens now.
 */
class CraftingGraphCacheFailureBoundaryTest {

    @TempDir Path dir;
    private Path cacheFile;
    private String previousCachePath;

    private final List<Recipe> recipes = new ArrayList<>();
    private final Map<Integer, String> names = new HashMap<>();
    private SQLException recipeFailure;
    private int databaseRecipeReads;
    private int snapshotRecipeReads;
    private int itemNameReads;

    private final RecipeRepository recipeRepo = new RecipeRepository() {
        @Override public List<Recipe> loadAllRecipes() throws SQLException {
            databaseRecipeReads++;
            if (recipeFailure != null) throw recipeFailure;
            return List.copyOf(recipes);
        }
        @Override public List<Recipe> loadAllRecipes(Connection con) {
            snapshotRecipeReads++;
            return List.copyOf(recipes);
        }
        @Override public int countRecipes() { return recipes.size(); }
        @Override public int countRecipeIngredients() {
            return recipes.stream().mapToInt(r -> r.ingredients.size()).sum();
        }
    };

    private final ItemRepository itemRepo = new ItemRepository() {
        @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) { itemNameReads++; return infos(ids); }
        @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> ids) { return infos(ids); }
        private Map<Integer, ItemInfo> infos(Set<Integer> ids) {
            Map<Integer, ItemInfo> out = new HashMap<>();
            for (int id : ids) {
                if (names.containsKey(id)) out.put(id, new ItemInfo(id, names.get(id), null, null));
            }
            return out;
        }
    };

    @BeforeEach
    void pointCacheAtOwnedTempFile() {
        previousCachePath = System.getProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        cacheFile = dir.resolve("graph-cache.json");
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, cacheFile.toString());
        recipes.add(new Recipe(1, 20, 2, 0, "Cook", List.of(new Ingredient(10, 3))));
        names.put(10, "Cabbage");
        names.put(20, "Soup");
    }

    @AfterEach
    void restoreCachePath() {
        if (previousCachePath == null) System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        else System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, previousCachePath);
    }

    private CraftingGraphCache cache() { return new CraftingGraphCache(recipeRepo, itemRepo); }

    private static void assertSoupGraph(CraftingGraph graph) {
        assertEquals(1, graph.getRecipes().size());
        Recipe r = graph.getRecipes().get(0);
        assertEquals(1, r.recipeId);
        assertEquals(20, r.outputItemId);
        assertEquals(2, r.outputCount);
        assertEquals("Cook", r.disciplinesText);
        assertEquals(1, r.ingredients.size());
        assertEquals(10, r.ingredients.get(0).itemId);
        assertEquals(3, r.ingredients.get(0).count);
        assertEquals("Soup", graph.getItemNames().get(20));
        assertEquals("Cabbage", graph.getItemNames().get(10));
    }

    @Test
    void absentCacheFileIsRebuiltFromRepositoriesAndWrittenWithItsKey() throws Exception {
        assertFalse(Files.exists(cacheFile));

        CraftingGraph graph = cache().load();

        assertSoupGraph(graph);
        String json = Files.readString(cacheFile);
        assertTrue(json.contains("\"cacheKey\" : \"recipes=1;ingredients=1\""), json);
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(cacheFile), left.toList(), "no temporary file may be left beside the cache");
        }
    }

    @Test
    void existingCacheFileIsReadWithoutTouchingTheDatabaseAndMapsEveryField() throws Exception {
        cache().rebuild();
        databaseRecipeReads = 0;
        itemNameReads = 0;

        CraftingGraph fromDisk = cache().load();

        assertSoupGraph(fromDisk);
        assertEquals(0, databaseRecipeReads);
        assertEquals(0, itemNameReads);
    }

    @Test
    void storedCacheIsTrustedEvenWhenTheRecipeTableHasSinceChanged_KP16() throws Exception {
        cache().rebuild();
        recipes.add(new Recipe(2, 30, 1, 0, "", List.of()));

        // KP-16 current behaviour: no freshness check, so the new recipe is invisible until invalidate()
        CraftingGraph stale = cache().load();
        assertEquals(1, stale.getRecipes().size());

        CraftingGraphCache fresh = cache();
        fresh.invalidate();
        assertEquals(2, fresh.load().getRecipes().size());
    }

    @Test
    void cacheWithoutItemNamesLoadsThemFromItemsAndRewritesTheFile() throws Exception {
        Files.writeString(cacheFile, """
                {"cacheKey":"x","generatedAt":1,"recipes":[
                  {"recipeId":1,"outputItemId":20,"outputCount":2,"minRating":0,"disciplinesText":"Cook",
                   "ingredients":[{"itemId":10,"count":3}]}]}
                """);
        names.remove(20);   // an item with no stored name contributes no entry

        CraftingGraph graph = cache().load();

        assertEquals("Cabbage", graph.getItemNames().get(10));
        assertFalse(graph.getItemNames().containsKey(20));
        assertEquals(1, itemNameReads);
        String rewritten = Files.readString(cacheFile);
        assertTrue(rewritten.contains("Cabbage"), rewritten);
    }

    @Test
    void secondLoadReturnsTheSameSnapshotAndInvalidateDropsIt() throws Exception {
        CraftingGraphCache cache = cache();
        CraftingGraph first = cache.load();
        assertSame(first, cache.load());

        cache.invalidate();
        CraftingGraph rebuilt = cache.load();

        assertNotSame(first, rebuilt);
        assertEquals(2, databaseRecipeReads, "initial build plus the rebuild after invalidate");
    }

    @Test
    void corruptCacheFileFailsEveryLoadWithoutRebuilding_KP16() throws Exception {
        Files.writeString(cacheFile, "{ this is not json");
        CraftingGraphCache cache = cache();

        assertThrows(JsonProcessingException.class, cache::load);
        assertThrows(JsonProcessingException.class, cache::load);

        assertEquals(0, databaseRecipeReads, "corrupt derived data is not treated as rebuildable today");
        assertEquals("{ this is not json", Files.readString(cacheFile));
    }

    @Test
    void failedRebuildPropagatesAndLeavesTheExistingCacheFileUntouched() throws Exception {
        CraftingGraphCache cache = cache();
        cache.rebuild();
        String before = Files.readString(cacheFile);
        recipeFailure = new SQLException("database unavailable");
        cache.invalidate();

        SQLException thrown = assertThrows(SQLException.class, cache::load);

        assertEquals("database unavailable", thrown.getMessage());
        assertEquals(before, Files.readString(cacheFile));
        // still invalidated: the next load tries the database again and succeeds once it is back
        recipeFailure = null;
        assertSoupGraph(cache.load());
    }

    @Test
    void unwritableCacheLocationFailsTheRebuildAndLeavesNoTemporaryFile() throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a regular file, not a directory");
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, blocker.resolve("graph.json").toString());

        assertThrows(IOException.class, () -> cache().load());

        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(blocker), left.filter(p -> !p.equals(cacheFile)).toList());
        }
    }

    @Test
    void blankCachePathOverrideFallsBackToTheDefaultFileName() throws Exception {
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, "  ");
        Path defaultFile = Path.of("crafting_graph_cache.json").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.exists(defaultFile),
                "refusing to touch a pre-existing working-directory cache");
        // The default location is not test-owned: prove only that a failing rebuild never reaches
        // the temp directory, by making the database read fail before anything is written.
        recipeFailure = new SQLException("stop before writing");
        CraftingGraphCache cache = cache();
        cache.invalidate();

        assertThrows(SQLException.class, cache::load);

        assertFalse(Files.exists(defaultFile));
        assertFalse(Files.exists(cacheFile));
    }

    @Test
    void snapshotLoadReadsThroughTheGivenConnectionAndNeverTouchesTheSharedCache() throws Exception {
        CraftingGraphCache cache = cache();

        CraftingGraph snapshot = cache.loadForSnapshot(fakeConnection());

        assertSoupGraph(snapshot);
        assertEquals(1, snapshotRecipeReads);
        assertEquals(0, databaseRecipeReads);
        assertFalse(Files.exists(cacheFile), "a pinned-snapshot read must not publish the shared cache");
    }

    @Test
    void snapshotLoadWithoutConnectionIsTheSharedCachedGraph() throws Exception {
        CraftingGraphCache cache = cache();
        assertSame(cache.load(), cache.loadForSnapshot(null));
    }

    /** The overridden repositories ignore the connection; only identity matters for the snapshot path. */
    private static Connection fakeConnection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }
}
