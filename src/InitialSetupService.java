import sync.AccountSync;
import sync.IconSync;
import sync.RecipeSync;
import sync.TpSync;

import java.nio.file.Path;

public final class InitialSetupService {
    private InitialSetupService() {}

    public static void firstFill() throws Exception {

        // (optional but recommended so your account tables exist right away)
        AccountSync.syncAccountBank();
        AccountSync.syncAccountMaterials();
        AccountSync.syncAccountRecipes();

        // Global recipes + ingredients + items (SAFE ordering)
        RecipeSync.syncAllRecipesGlobalSafe();

        // Then prices/icons (now DB is consistent)
        TpSync.syncTpPricesRelevant();
        IconSync.syncItemIconUrls();
        IconSync.syncItemIconsToDisk(Path.of("C:\\Users\\Administrator\\AppData\\Local\\NebetGw2Tool\\icons"));
    }
}