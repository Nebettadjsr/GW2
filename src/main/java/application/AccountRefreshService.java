package application;

import sync.AccountRefreshGateway;
import repo.AccountSyncStateRepository;
import java.util.Set;
import java.time.Instant;

/**
 * Application-layer use case for account synchronization (TARGET_ARCHITECTURE.md §8), owning the
 * two pre-existing, distinctly scoped account-refresh sequences:
 *
 * <ul>
 *   <li>{@link #refreshAll()} - the Sync Account flow (STORY-APP-004): account bank, account
 *       materials, account recipes, consumed account Luck, then every character's crafting ratings
 *       and recipes. Luck extends the original {@code Gw2App} button sequence. Also the
 *       sequence {@code CraftingDiscoveryView}'s periodic auto-refresh already ran
 *       (STORY-APP-008).</li>
 *   <li>{@link #refreshMaterialsAndRecipes()} - the narrower sequence
 *       {@code CraftingProfitView}'s periodic auto-refresh already ran (STORY-APP-008): account
 *       materials then account recipes only, deliberately without the bank and character steps.</li>
 * </ul>
 *
 * <p>Holds no JavaFX dependency and performs no calculation; it only coordinates the
 * {@link AccountRefreshGateway} collaborator. A failure from any step propagates immediately and
 * short-circuits the remaining steps, matching the original straight-line call sequences (no
 * partial-completion handling existed before these extractions, so none is introduced here).
 *
 * <p>Replaces the previously unused top-level {@code AccountRefreshService}, which duplicated a
 * subset of this orchestration but had no call site in {@code Gw2App}
 * (docs/KNOWN_PROBLEMS.md §8).
 */
public class AccountRefreshService {

    private final AccountRefreshGateway gateway;
    private volatile Instant lastRefreshedAt;
    private volatile String lastRefreshScope;

    public AccountRefreshService() {
        this(new AccountRefreshGateway());
    }

    /** Seam used by application-layer tests to substitute a fake gateway (TARGET_ARCHITECTURE.md §25). */
    public AccountRefreshService(AccountRefreshGateway gateway) {
        this.gateway = gateway;
    }

    public void refreshAll() throws Exception {
        gateway.syncAccountBank();
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
        gateway.syncAccountLuck();
        gateway.syncCharacterCraftingAndRecipes();
        markRefreshed("full account");
    }

    public void refreshMaterialsAndRecipes() throws Exception {
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
        markRefreshed("materials and recipes");
    }

    /** Refreshes the account datasets consumed by Crafting Profit without the unrelated Luck API call. */
    public void refreshCraftingProfitData() throws Exception {
        gateway.syncAccountBank();
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
        gateway.syncCharacterCraftingAndRecipes();
        markRefreshed("Crafting Profit inputs");
    }

    /** Refreshes just the stale sources required by the requesting use case. */
    public void refreshSources(Set<String> sources) throws Exception {
        if (sources.contains(AccountSyncStateRepository.BANK)) gateway.syncAccountBank();
        if (sources.contains(AccountSyncStateRepository.MATERIALS)) gateway.syncAccountMaterials();
        if (sources.contains(AccountSyncStateRepository.RECIPES)) gateway.syncAccountRecipes();
        if (sources.contains(AccountSyncStateRepository.LUCK)) gateway.syncAccountLuck();
        if (sources.contains(AccountSyncStateRepository.CHARACTERS)) gateway.syncCharacterCraftingAndRecipes();
    }

    private void markRefreshed(String scope) {
        lastRefreshScope = scope;
        lastRefreshedAt = Instant.now();
    }

    public Instant lastRefreshedAt() { return lastRefreshedAt; }
    public String lastRefreshScope() { return lastRefreshScope; }
}
