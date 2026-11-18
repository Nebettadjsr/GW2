package application;

import repo.CraftingGraphCache;

/**
 * Application-layer use case for rebuilding the persisted crafting graph cache. Owns invocation of the repository-backed {@link CraftingGraphCache#rebuild()} and introduces no
 * competing graph-building algorithm; {@link CraftingGraphCache} - an instantiable, non-final,
 * overridable class - is this service's sole replaceable collaborator, substituted with a fake
 * subclass in application-layer tests.
 */
public class CraftingGraphRebuildService {

    private final CraftingGraphCache cache;

    public CraftingGraphRebuildService(CraftingGraphCache cache) {
        this.cache = cache;
    }

    public void rebuild() throws Exception {
        cache.rebuild();
    }

    /** Full-catalog-task rebuild bounded by the task's remaining budget (see the cache method). */
    public void rebuild(api.TaskDeadline deadline) throws Exception {
        cache.rebuild(deadline);
    }

    public void invalidate() {
        cache.invalidate();
    }
}
