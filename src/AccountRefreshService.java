import java.nio.file.Path;

public final class AccountRefreshService {
    private AccountRefreshService() {}

    public static void refreshAll() throws Exception {
        // account data
        Gw2DbSync.syncAccountBank();
        Gw2DbSync.syncAccountMaterials();
        Gw2DbSync.syncAccountRecipes();
        Gw2DbSync.syncRecipesAndIngredients();
        Gw2DbSync.syncItems();
        Gw2DbSync.syncTpPricesRelevant();
        Gw2DbSync.syncItemIconUrls();
        Gw2DbSync.syncItemIconsToDisk(Path.of("C:\\Users\\Bianca\\GW2Data\\icons"));

    }
}
