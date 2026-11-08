package application;

import api.TaskDeadline;
import repo.CraftingGraphCache;
import repo.RecipeRepository;
import sync.FullCatalogGateway;

import java.util.function.Supplier;

/**
 * The three owner-only full-catalog operations (STORY-STATUS-004). Each call starts its own fixed
 * {@value TaskDeadline#TASK_MINUTES}-minute cooperative bound when the task body begins.
 *
 * <p>Recipes: after a successful full recipe synchronization the shared graph cache is invalidated
 * and then rebuilt. A failed rebuild leaves the cache invalidated, never serving stale recipe
 * relationships, and a failure after recipe rows may have changed also invalidates it. No
 * last-good graph is kept.
 */
public class FullCatalogAdministrationService {

    private final FullCatalogGateway gateway;
    private final CraftingGraphRebuildService graph;
    private final Supplier<TaskDeadline> deadlines;

    public FullCatalogAdministrationService(CraftingGraphCache graphCache) {
        this(new FullCatalogGateway(), new CraftingGraphRebuildService(graphCache), TaskDeadline::start);
    }

    /** Test seam: fake gateway, graph service and clock-controlled deadlines. */
    public FullCatalogAdministrationService(FullCatalogGateway gateway, CraftingGraphRebuildService graph,
                                            Supplier<TaskDeadline> deadlines) {
        this.gateway = gateway;
        this.graph = graph;
        this.deadlines = deadlines;
    }

    public void syncItems() throws Exception {
        gateway.syncAllItems(deadlines.get());
    }

    public void syncTradingPost() throws Exception {
        gateway.syncAllTradingPost(deadlines.get());
    }

    public void syncRecipes() throws Exception {
        TaskDeadline deadline = deadlines.get();
        try {
            gateway.syncAllRecipes(deadline);
            deadline.check();
            graph.invalidate();
            deadline.check();
            graph.rebuild();
        } catch (Exception | Error failure) {
            graph.invalidate();
            throw failure;
        }
    }
}
