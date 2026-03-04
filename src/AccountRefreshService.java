import sync.AccountSync;
import sync.CharacterSync;

import java.nio.file.Path;

public final class AccountRefreshService {
    private AccountRefreshService() {}

    public static void refreshAll() throws Exception {
        AccountSync.syncAccountBank();
        AccountSync.syncAccountMaterials();
        AccountSync.syncAccountRecipes();
        CharacterSync.syncCharactersCraftingAndRecipes();
    }
}