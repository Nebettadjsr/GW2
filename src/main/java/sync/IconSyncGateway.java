package sync;

import java.nio.file.Path;

/**
 * Thin instantiable seam over the static {@link IconSync} icon-sync calls (STORY-APP-007),
 * mirroring {@link AccountRefreshGateway}/{@link GlobalDataRefreshGateway}/
 * {@link TradingPostPriceRefreshGateway}'s precedent: {@code IconSync} is a non-instantiable
 * utility class with no overridable methods, so this gateway exists purely to give
 * {@code application.InitialSetupService} a replaceable collaborator: a fake subclass
 * substitutes for it in application-layer tests (TARGET_ARCHITECTURE.md §25) without any live
 * GW2 API or database access.
 */
public class IconSyncGateway {

    public void syncItemIconUrls() throws Exception {
        IconSync.syncItemIconUrls();
    }

    public void syncItemIconsToDisk(Path iconBaseDir) throws Exception {
        IconSync.syncItemIconsToDisk(iconBaseDir);
    }
}
