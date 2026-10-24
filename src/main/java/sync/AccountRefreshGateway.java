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

    public void syncAccountLuck() throws Exception {
        AccountSync.syncAccountLuck();
    }

    public void syncCharacterCraftingAndRecipes() throws Exception {
        CharacterSync.syncCharactersCraftingAndRecipes();
    }

    // The account-scoped sequences (STORY-APP-015). Each fetches outside the database transaction
    // and then publishes that one source atomically, so a failure in one leaves the others' already
    // committed publications intact.

    public void syncAccountBank(model.AccountScope scope) throws Exception {
        AccountSync.syncAccountBank(scope);
    }

    public void syncAccountMaterials(model.AccountScope scope) throws Exception {
        AccountSync.syncAccountMaterials(scope);
    }

    public void syncAccountRecipes(model.AccountScope scope) throws Exception {
        AccountSync.syncAccountRecipes(scope);
    }

    public void syncAccountLuck(model.AccountScope scope) throws Exception {
        AccountSync.syncAccountLuck(scope);
    }

    public void syncCharacterCraftingAndRecipes(model.AccountScope scope) throws Exception {
        CharacterSync.syncCharactersCraftingAndRecipes(scope);
    }
}
