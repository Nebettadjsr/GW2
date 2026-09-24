package application;

import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.RecipeRepository;
import sync.GlobalDataRefreshGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-005) for
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

        assertEquals(List.of("tradeableItems", "globalRecipes"), gateway.calls);
        assertEquals(1, graphCache.rebuildCount);
    }

    @Test
    void tradeableItemsFailureShortCircuitsRecipesAndGraphRebuild() {
        var gateway = new RecordingGateway();
        gateway.tradeableItemsFailure = new IOException("simulated tradeable-item fetch failure");
        var graphCache = new RecordingCache();
        var service = new GlobalDataRefreshService(gateway, new CraftingGraphRebuildService(graphCache));

        Exception thrown = assertThrows(IOException.class, service::refreshAll);

        assertSame(gateway.tradeableItemsFailure, thrown);
        assertEquals(List.of(), gateway.calls);
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
        assertEquals(List.of("tradeableItems"), gateway.calls);
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
        assertEquals(List.of("tradeableItems", "globalRecipes"), gateway.calls);
    }

    private static class RecordingGateway extends GlobalDataRefreshGateway {
        final List<String> calls = new ArrayList<>();
        Exception tradeableItemsFailure;
        Exception globalRecipesFailure;

        @Override
        public void syncTpTradeableItems() throws Exception {
            if (tradeableItemsFailure != null) throw tradeableItemsFailure;
            calls.add("tradeableItems");
        }

        @Override
        public void syncAllRecipesGlobalSafe() throws Exception {
            if (globalRecipesFailure != null) throw globalRecipesFailure;
            calls.add("globalRecipes");
        }
    }

    private static class RecordingCache extends CraftingGraphCache {
        int rebuildCount = 0;
        IOException failure;

        RecordingCache() {
            super(new RecipeRepository());
        }

        @Override
        public craft.CraftingGraph rebuild() throws IOException {
            if (failure != null) throw failure;
            rebuildCount++;
            return new craft.CraftingGraph(List.of());
        }
    }
}
