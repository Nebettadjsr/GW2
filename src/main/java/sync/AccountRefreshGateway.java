package sync;

/**
 * Thin instantiable seam over the static {@link AccountSync}/{@link CharacterSync} account-refresh
 * calls (STORY-APP-004). {@code AccountSync} and {@code CharacterSync} are non-instantiable
 * utility classes with no overridable methods, so this gateway exists purely to give
 * {@code application.AccountRefreshService} a replaceable collaborator: a fake subclass
 * substitutes for it in application-layer tests (TARGET_ARCHITECTURE.md §25) without any live GW2
 * API or database access.
 */
public class AccountRefreshGateway {

    public void syncAccountBank() throws Exception {
        AccountSync.syncAccountBank();
    }

    public void syncAccountMaterials() throws Exception {
        AccountSync.syncAccountMaterials();
    }

    public void syncAccountRecipes() throws Exception {
        AccountSync.syncAccountRecipes();
    }

    public void syncCharacterCraftingAndRecipes() throws Exception {
        CharacterSync.syncCharactersCraftingAndRecipes();
    }
}
