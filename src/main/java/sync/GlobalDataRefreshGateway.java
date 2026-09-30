package sync;

/**
 * Thin instantiable seam over the static {@link TpSync}/{@link RecipeSync} global-data-refresh
 * calls (STORY-APP-005), mirroring {@link AccountRefreshGateway}'s precedent (STORY-APP-004):
 * {@code TpSync} and {@code RecipeSync} are non-instantiable utility classes with no overridable
 * methods, so this gateway exists purely to give {@code application.GlobalDataRefreshService} a
 * replaceable collaborator: a fake subclass substitutes for it in application-layer tests
 * (TARGET_ARCHITECTURE.md §25) without any live GW2 API or database access.
 */
public class GlobalDataRefreshGateway {

    public boolean syncTpTradeableItems() throws Exception {
        return TpSync.syncTpTradeableItems();
    }

    public boolean syncAllRecipesGlobalSafe() throws Exception {
        return RecipeSync.syncAllRecipesGlobalSafe();
    }
}
