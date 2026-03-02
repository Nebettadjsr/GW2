import java.nio.file.Path;

public final class AccountRefreshService {
    private AccountRefreshService() {}

    public static void refreshAll() throws Exception {
        Gw2DbSync.syncAccountBank();
        Gw2DbSync.syncAccountMaterials();
        Gw2DbSync.syncAccountRecipes();
        Gw2DbSync.syncCharactersCraftingAndRecipes();
    }
}