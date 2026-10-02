package application;

import repo.CraftingGraphCache;
import repo.RecipeRepository;
import sync.GlobalDataRefreshGateway;
import java.time.Instant;

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
    private volatile Instant lastCheckedAt;
    private volatile Instant lastChangedAt;
    private volatile Instant lastRecipeSyncAt;
    private volatile Instant lastGraphRebuildAt;
    private volatile String lastFailure;
    private volatile boolean running;

    public GlobalDataRefreshService() {
        this(new GlobalDataRefreshGateway(),
             new CraftingGraphRebuildService(new CraftingGraphCache(new RecipeRepository())));
    }

    /** Refresh path sharing the same graph cache used by the web crafting calculation factories. */
    public GlobalDataRefreshService(CraftingGraphCache graphCache) {
        this(new GlobalDataRefreshGateway(), new CraftingGraphRebuildService(graphCache));
    }

    /** Seam used by application-layer tests to substitute fake collaborators (TARGET_ARCHITECTURE.md §25). */
    public GlobalDataRefreshService(GlobalDataRefreshGateway gateway, CraftingGraphRebuildService graphRebuildService) {
        this.gateway = gateway;
        this.graphRebuildService = graphRebuildService;
    }

    public RefreshResult refreshAll() throws Exception {
        running = true;
        try {
            boolean tradeableItemsChanged = gateway.syncTpTradeableItems();
            boolean recipesChanged = gateway.syncAllRecipesGlobalSafe();
            if (recipesChanged) {
                lastRecipeSyncAt = Instant.now();
                lastChangedAt = lastRecipeSyncAt;
                graphRebuildService.invalidate();
                graphRebuildService.rebuild();
                lastGraphRebuildAt = Instant.now();
            }
            if (tradeableItemsChanged || recipesChanged) lastChangedAt = Instant.now();
            lastCheckedAt = Instant.now();
            lastFailure = null;
            return new RefreshResult(tradeableItemsChanged, recipesChanged, recipesChanged);
        } catch (Exception failure) {
            lastCheckedAt = Instant.now();
            lastFailure = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            running = false;
        }
    }

    public record RefreshResult(boolean tradeableItemsChanged, boolean recipesChanged, boolean graphRebuilt) {}
    public record Status(boolean running, Instant lastCheckedAt, Instant lastChangedAt,
                         Instant lastRecipeSyncAt, Instant lastGraphRebuildAt, String lastFailure) {}

    public Status status() {
        return new Status(running, lastCheckedAt, lastChangedAt, lastRecipeSyncAt, lastGraphRebuildAt, lastFailure);
    }
}
