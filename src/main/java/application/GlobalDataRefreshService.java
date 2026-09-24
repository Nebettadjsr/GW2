package application;

import repo.CraftingGraphCache;
import repo.RecipeRepository;
import sync.GlobalDataRefreshGateway;

/**
 * Application-layer use case for the "Sync ALL tradeable Items, Recipes and (re)build Crafting
 * Graph" flow (TARGET_ARCHITECTURE.md §8, STORY-APP-005): refreshes the tradeable-item id list,
 * then global recipes, then delegates the crafting-graph-cache rebuild to
 * {@link CraftingGraphRebuildService} - in that exact order, unchanged from the pre-extraction
 * {@code Gw2App} button handler. Holds no JavaFX dependency and performs no calculation itself;
 * it only coordinates its two collaborators. A failure from any step propagates immediately and
 * short-circuits the remaining steps, matching the original straight-line call sequence (no
 * partial-completion handling existed before this extraction, so none is introduced here).
 */
public class GlobalDataRefreshService {

    private final GlobalDataRefreshGateway gateway;
    private final CraftingGraphRebuildService graphRebuildService;

    public GlobalDataRefreshService() {
        this(new GlobalDataRefreshGateway(),
             new CraftingGraphRebuildService(new CraftingGraphCache(new RecipeRepository())));
    }

    /** Seam used by application-layer tests to substitute fake collaborators (TARGET_ARCHITECTURE.md §25). */
    public GlobalDataRefreshService(GlobalDataRefreshGateway gateway, CraftingGraphRebuildService graphRebuildService) {
        this.gateway = gateway;
        this.graphRebuildService = graphRebuildService;
    }

    public void refreshAll() throws Exception {
        gateway.syncTpTradeableItems();
        gateway.syncAllRecipesGlobalSafe();
        graphRebuildService.rebuild();
    }
}
