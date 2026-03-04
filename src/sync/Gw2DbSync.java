package sync;

public final class Gw2DbSync {
    private Gw2DbSync() {}

    public static void syncAccountAll() throws Exception {
        AccountSync.syncAccountBank();
        AccountSync.syncAccountMaterials();
        AccountSync.syncAccountRecipes();
    }

    // später mehr: characters, items, recipes, tp, icons...
}