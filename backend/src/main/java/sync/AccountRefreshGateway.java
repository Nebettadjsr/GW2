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

    // The account-scoped sequences (STORY-APP-015, STORY-SYNC-006). Each fetches outside the
    // database transaction with the supplied operation credential - never the deployment key - and
    // then publishes that one source atomically, so a failure in one leaves the others' already
    // committed publications intact.

    public void syncAccountBank(model.AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        AccountSync.syncAccountBankImpl(scope, fetcher, this::repairItemMetadata);
    }

    public void syncAccountMaterials(model.AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        AccountSync.syncAccountMaterialsImpl(scope, fetcher, this::repairItemMetadata);
    }

    public void syncAccountRecipes(model.AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        AccountSync.syncAccountRecipes(scope, fetcher);
    }

    public void syncAccountLuck(model.AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        AccountSync.syncAccountLuck(scope, fetcher);
    }

    public void syncCharacterCraftingAndRecipes(model.AccountScope scope, AccountSync.AuthFetcher fetcher)
            throws Exception {
        CharacterSync.syncCharactersCraftingAndRecipes(scope, fetcher, this::repairItemMetadata);
    }

    /** Fetch public metadata before publishing new account IDs, outside the account transaction. */
    private void repairItemMetadata(java.util.Collection<Integer> ids) throws Exception {
        try (java.sql.Connection con = repo.Db.open()) {
            IconSync.repairMissingMetadata(con, ids, this::fetchItemMetadata);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            System.out.println("Account item metadata unavailable; retry on the next synchronization: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    /** Replaceable upstream edge, including for full HTTP/database fixture checks. */
    protected com.fasterxml.jackson.databind.JsonNode fetchItemMetadata(java.util.List<Integer> ids) throws Exception {
        return IconSync.fetchItemMetadata(ids);
    }
}
