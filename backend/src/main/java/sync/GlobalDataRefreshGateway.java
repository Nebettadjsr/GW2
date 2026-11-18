package sync;

/**
 * Thin instantiable seam over the static global-data-refresh calls ({@link MaterialCategorySync},
 * {@link IconSync}, {@link TpSync}, {@link RecipeSync}), mirroring {@link AccountRefreshGateway}.
 * Those classes are non-instantiable utilities, so this gateway gives
 * {@code application.GlobalDataRefreshService} a replaceable collaborator: a fake subclass
 * substitutes for it in application-layer tests without any live GW2 API or database access.
 */
public class GlobalDataRefreshGateway {

    public void syncMaterialCategories() throws Exception {
        MaterialCategorySync.syncMaterialCategories();
    }

    public void syncMaterialItemMetadata() throws Exception {
        IconSync.syncItemIconUrls();
    }

    public boolean syncTpTradeableItems() throws Exception {
        return TpSync.syncTpTradeableItems();
    }

    public boolean syncAllRecipesGlobalSafe() throws Exception {
        return RecipeSync.syncAllRecipesGlobalSafe();
    }
}
