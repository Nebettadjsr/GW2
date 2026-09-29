package application;

import repo.AppConfig;
import sync.AccountRefreshGateway;
import sync.GlobalDataRefreshGateway;
import sync.IconSyncGateway;

import java.nio.file.Path;

/**
 * Application-layer use case for the "First-time DB Setup" flow (TARGET_ARCHITECTURE.md §8,
 * STORY-APP-007): account bank/materials/recipes/Luck, then global recipes, then TP tradeable items,
 * then discovery/profit price refresh, then icon URLs and icon disk download. Luck extends the
 * original setup sequence.
 * Holds no JavaFX dependency and performs no calculation itself; it only coordinates its
 * collaborators. A failure from any step propagates immediately and short-circuits the
 * remaining steps, matching the original straight-line call sequence (no partial-completion
 * handling existed before this extraction, so none is introduced here).
 *
 * <p>Reuses {@link AccountRefreshGateway} (bank/materials/recipes/Luck only - not the character
 * crafting/recipes step {@code application.AccountRefreshService} adds) and
 * {@link GlobalDataRefreshGateway} (global recipes and tradeable items only - not the graph
 * rebuild {@code application.GlobalDataRefreshService} adds), since setup's step selection and
 * order differ from both of those broader use cases (STORY-APP-004/STORY-APP-005). Reuses
 * {@link TradingPostPriceRefreshService} unchanged (STORY-APP-006).
 *
 * <p>Replaces the previously separate top-level {@code InitialSetupService}, which duplicated
 * this orchestration outside the application layer; that class has been deleted so one
 * authoritative setup workflow remains.
 */
public class InitialSetupService {

    private final AccountRefreshGateway accountGateway;
    private final GlobalDataRefreshGateway globalDataGateway;
    private final TradingPostPriceRefreshService priceRefreshService;
    private final IconSyncGateway iconGateway;

    public InitialSetupService() {
        this(new AccountRefreshGateway(), new GlobalDataRefreshGateway(),
             new TradingPostPriceRefreshService(), new IconSyncGateway());
    }

    /** Seam used by application-layer tests to substitute fake collaborators (TARGET_ARCHITECTURE.md §25). */
    public InitialSetupService(AccountRefreshGateway accountGateway,
                                GlobalDataRefreshGateway globalDataGateway,
                                TradingPostPriceRefreshService priceRefreshService,
                                IconSyncGateway iconGateway) {
        this.accountGateway = accountGateway;
        this.globalDataGateway = globalDataGateway;
        this.priceRefreshService = priceRefreshService;
        this.iconGateway = iconGateway;
    }

    public void firstFill() throws Exception {
        accountGateway.syncAccountBank();
        accountGateway.syncAccountMaterials();
        accountGateway.syncAccountRecipes();
        accountGateway.syncAccountLuck();

        globalDataGateway.syncAllRecipesGlobalSafe();
        globalDataGateway.syncTpTradeableItems();

        priceRefreshService.refreshForDiscovery();
        priceRefreshService.refreshForProfit();

        iconGateway.syncItemIconUrls();
        iconGateway.syncItemIconsToDisk(Path.of(AppConfig.ICON_CACHE_DIR));
    }
}
