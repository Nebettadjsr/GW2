package application;

import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.RecipeRepository;
import sync.GlobalDataRefreshGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (ARCHITECTURE.md section 2, STORY-APP-005) for
 * {@link GlobalDataRefreshService}: a fake {@link GlobalDataRefreshGateway} and a fake
 * {@link CraftingGraphRebuildService} (backed by a recording {@link CraftingGraphCache} subclass)
 * replace the live GW2 API/database calls, so these run without HTTP or PostgreSQL. Proves the
 * exact pre-extraction call order (tradeable items, then global recipes, then one graph-rebuild
 * delegation) and that a failure from any step propagates immediately and short-circuits the
 * remaining steps - the same behavior the removed {@code Gw2App} inline sequence had.
 */
class GlobalDataRefreshServiceTest {

    @Test
    void refreshAllInvokesEachStepInTheExistingOrderWithOneGraphRebuild() throws Exception {
        var gateway = new RecordingGateway();
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        service.refreshAll();

        assertEquals(List.of("materialCategories", "materialIcons", "tradeableItems", "globalRecipes"), gateway.calls);
        assertEquals(List.of("invalidate", "rebuild"), graphCache.graphCalls);
        assertEquals(1, graphCache.rebuildCount);
    }

    @Test
    void unchangedRecipeDataDoesNotRebuildTheGraph() throws Exception {
        var gateway = new RecordingGateway();
        gateway.recipesChanged = false;
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        var result = service.refreshAll();

        assertEquals(List.of("materialCategories", "materialIcons", "tradeableItems", "globalRecipes"), gateway.calls);
        assertEquals(0, graphCache.rebuildCount);
        assertEquals(List.of(), graphCache.graphCalls);
        assertEquals(false, result.graphRebuilt());
    }

    @Test
    void materialCategoryFailureStopsLaterWorkRecordsSafeStatusAndAllowsExplicitRetry() throws Exception {
        var gateway = new RecordingGateway();
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));
        gateway.materialCategoriesFailure = new IOException("private upstream diagnostic must not become status");
        gateway.onMaterialCategories = () -> assertTrue(service.status().running(), "the active refresh is visible");

        var thrown = assertThrows(IOException.class, service::refreshAll);

        assertSame(gateway.materialCategoriesFailure, thrown);
        assertEquals(List.of("materialCategories"), gateway.calls);
        assertEquals(List.of(), graphCache.graphCalls);
        assertEquals(0, graphCache.rebuildCount);
        var failed = service.status();
        assertFalse(failed.running());
        assertNotNull(failed.lastCheckedAt());
        assertEquals("IOException", failed.lastFailure(), "status reports only the safe exception type");
        assertNull(failed.lastChangedAt());
        assertNull(failed.lastRecipeSyncAt());
        assertNull(failed.lastGraphRebuildAt());

        gateway.materialCategoriesFailure = null;
        var result = service.refreshAll();

        assertEquals(List.of("materialCategories", "materialCategories", "materialIcons", "tradeableItems", "globalRecipes"), gateway.calls);
        assertEquals(List.of("invalidate", "rebuild"), graphCache.graphCalls);
        assertEquals(1, graphCache.rebuildCount);
        assertEquals(new GlobalDataRefreshService.RefreshResult(true, true, true), result);
        var recovered = service.status();
        assertFalse(recovered.running());
        assertNull(recovered.lastFailure());
        assertNotNull(recovered.lastCheckedAt());
        assertNotNull(recovered.lastChangedAt());
        assertNotNull(recovered.lastRecipeSyncAt());
        assertNotNull(recovered.lastGraphRebuildAt());
    }

    @Test
    void tradeableItemsFailureShortCircuitsRecipesAndGraphRebuild() {
        var gateway = new RecordingGateway();
        gateway.tradeableItemsFailure = new IOException("simulated tradeable-item fetch failure");
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        Exception thrown = assertThrows(IOException.class, service::refreshAll);

        assertSame(gateway.tradeableItemsFailure, thrown);
        assertEquals(List.of("materialCategories", "materialIcons"), gateway.calls);
        assertEquals(0, graphCache.rebuildCount);
    }

    @Test
    void globalRecipesFailureShortCircuitsGraphRebuildButTradeableItemsAlreadyRan() {
        var gateway = new RecordingGateway();
        gateway.globalRecipesFailure = new RuntimeException("simulated recipe sync failure");
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        Exception thrown = assertThrows(RuntimeException.class, service::refreshAll);

        assertSame(gateway.globalRecipesFailure, thrown);
        assertEquals(List.of("materialCategories", "materialIcons", "tradeableItems"), gateway.calls);
        assertEquals(0, graphCache.rebuildCount);
    }

    @Test
    void graphRebuildFailurePropagatesAfterBothSyncStepsRan() {
        var gateway = new RecordingGateway();
        var graphCache = new RecordingCache();
        graphCache.failure = new IOException("simulated cache rebuild failure");
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        Exception thrown = assertThrows(IOException.class, service::refreshAll);

        assertSame(graphCache.failure, thrown);
        assertEquals(List.of("materialCategories", "materialIcons", "tradeableItems", "globalRecipes"), gateway.calls);
    }

    private static class RecordingGateway extends GlobalDataRefreshGateway {
        final List<String> calls = new ArrayList<>();
        Exception materialCategoriesFailure;
        Runnable onMaterialCategories = () -> {};
        Exception tradeableItemsFailure;
        Exception globalRecipesFailure;
        boolean recipesChanged = true;

        @Override public void syncMaterialCategories() throws Exception {
            calls.add("materialCategories");
            onMaterialCategories.run();
            if (materialCategoriesFailure != null) throw materialCategoriesFailure;
        }
        @Override public void syncMaterialItemMetadata() { calls.add("materialIcons"); }

        @Override
        public boolean syncTpTradeableItems() throws Exception {
            if (tradeableItemsFailure != null) throw tradeableItemsFailure;
            calls.add("tradeableItems");
            return true;
        }

        @Override
        public boolean syncAllRecipesGlobalSafe() throws Exception {
            if (globalRecipesFailure != null) throw globalRecipesFailure;
            calls.add("globalRecipes");
            return recipesChanged;
        }
    }

    private static class RecordingCache extends CraftingGraphCache {
        int rebuildCount = 0;
        final List<String> graphCalls = new ArrayList<>();
        IOException failure;

        RecordingCache() {
            super(new RecipeRepository());
        }

        @Override
        public craft.CraftingGraph rebuild() throws IOException {
            if (failure != null) throw failure;
            rebuildCount++;
            graphCalls.add("rebuild");
            return new craft.CraftingGraph(List.of());
        }

        @Override public synchronized void invalidate() {
            graphCalls.add("invalidate");
            super.invalidate();
        }
    }
}
