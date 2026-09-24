package application;

import repo.CraftingGraphCache;

/**
 * Application-layer use case for rebuilding the persisted crafting graph cache
 * (TARGET_ARCHITECTURE.md §8, STORY-APP-005): owns invocation of the existing
 * repository-backed {@link CraftingGraphCache#rebuild()}, unchanged from the pre-extraction call
 * made directly by {@code Gw2App}'s "Sync ALL tradeable Items..." button handler. Holds no JavaFX
 * dependency and introduces no competing graph-building algorithm; {@link CraftingGraphCache} -
 * already an instantiable, non-final, overridable class - is this service's sole replaceable
 * collaborator, substituted with a fake subclass in application-layer tests.
 */
public class CraftingGraphRebuildService {

    private final CraftingGraphCache cache;

    public CraftingGraphRebuildService(CraftingGraphCache cache) {
        this.cache = cache;
    }

    public void rebuild() throws Exception {
        cache.rebuild();
    }
}
