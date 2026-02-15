import java.nio.file.Path;

public final class InitialSetupService {
    private InitialSetupService() {}

    public static void firstFill() throws Exception {

        // (optional but recommended so your account tables exist right away)
        Gw2DbSync.syncAccountBank();
        Gw2DbSync.syncAccountMaterials();
        Gw2DbSync.syncAccountRecipes();

        // Global recipes + ingredients + items (SAFE ordering)
        Gw2DbSync.syncAllRecipesGlobalSafe();

        // Then prices/icons (now DB is consistent)
        Gw2DbSync.syncTpPricesRelevant(); // we’ll modify this method to only use account recipes
        Gw2DbSync.syncItemIconUrls();
        Gw2DbSync.syncItemIconsToDisk(Path.of("C:\\Users\\Administrator\\AppData\\Local\\NebetGw2Tool\\icons"));
    }
}