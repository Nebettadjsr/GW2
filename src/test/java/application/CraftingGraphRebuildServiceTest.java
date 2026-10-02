package application;

import craft.CraftingGraph;
import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.RecipeRepository;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Application-layer orchestration test (TARGET_ARCHITECTURE.md §25, STORY-APP-005) for
 * {@link CraftingGraphRebuildService}: a fake {@link CraftingGraphCache} subclass replaces the
 * real database-backed rebuild, so this runs without PostgreSQL. Proves delegation to the
 * existing cache rebuild happens exactly once and that its failure propagates unchanged, without
 * the service introducing a second graph-building calculation.
 */
class CraftingGraphRebuildServiceTest {

    @Test
    void rebuildDelegatesToCacheRebuildExactlyOnce() throws Exception {
        var cache = new RecordingCache();
        var service = new CraftingGraphRebuildService(cache);

        service.rebuild();

        assertEquals(1, cache.rebuildCount);
    }

    @Test
    void rebuildFailurePropagatesUnchanged() {
        var cache = new RecordingCache();
        cache.failure = new IOException("simulated cache rebuild failure");
        var service = new CraftingGraphRebuildService(cache);

        Exception thrown = assertThrows(IOException.class, service::rebuild);

        assertSame(cache.failure, thrown);
    }

    @Test
    void invalidationDelegatesToTheSharedCache() throws Exception {
        var cache = new RecordingCache();
        var service = new CraftingGraphRebuildService(cache);

        service.invalidate();
        service.rebuild();

        assertEquals(1, cache.invalidateCount);
        assertEquals(1, cache.rebuildCount);
    }

    private static class RecordingCache extends CraftingGraphCache {
        int rebuildCount = 0;
        int invalidateCount = 0;
        IOException failure;

        RecordingCache() {
            super(new RecipeRepository());
        }

        @Override public synchronized void invalidate() {
            invalidateCount++;
            super.invalidate();
        }

        @Override
        public CraftingGraph rebuild() throws IOException {
            if (failure != null) throw failure;
            rebuildCount++;
            return new CraftingGraph(List.of());
        }
    }
}
