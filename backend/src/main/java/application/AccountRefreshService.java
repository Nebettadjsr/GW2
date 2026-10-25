package application;

import model.AccountScope;
import sync.AccountRefreshGateway;
import sync.AccountSync;
import repo.AccountSyncStateRepository;
import java.util.List;
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

    /**
     * Refreshes the requested sources for one account (STORY-APP-015).
     *
     * <p>Unlike the unscoped {@link #refreshSources(Set)} above, a failing source does not
     * short-circuit the rest: each source is fetched and published independently, so one that
     * succeeds commits its own data, generation and completion even when another fails
     * (TARGET_ARCHITECTURE.md §7.2 "Successful sources may commit even when another source fails").
     * The first failure is then thrown with the remaining ones attached, so nothing is swallowed and
     * no whole-account completion is fabricated from a partial run.
     *
     * <p>The fixed order is the one the unscoped sequences already used; it carries no dependency
     * between sources, since every publication is its own transaction.
     */
    public void refreshSources(AccountScope scope, Set<String> sources, AccountSync.AuthFetcher fetcher)
            throws Exception {
        List<ScopedStep> steps = List.of(
                new ScopedStep(AccountSyncStateRepository.BANK, () -> gateway.syncAccountBank(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.MATERIALS, () -> gateway.syncAccountMaterials(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.RECIPES, () -> gateway.syncAccountRecipes(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.LUCK, () -> gateway.syncAccountLuck(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.CHARACTERS,
                        () -> gateway.syncCharacterCraftingAndRecipes(scope, fetcher)));

        Exception firstFailure = null;
        for (ScopedStep step : steps) {
            if (!sources.contains(step.source())) continue;
            try {
                step.run().run();
            } catch (Exception failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
        markRefreshed("account sources " + sources);
    }

    /** Refreshes every account source this service knows, for one account (STORY-APP-015). */
    public void refreshAll(AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
        refreshSources(scope, Set.of(
                AccountSyncStateRepository.BANK,
                AccountSyncStateRepository.MATERIALS,
                AccountSyncStateRepository.RECIPES,
                AccountSyncStateRepository.LUCK,
                AccountSyncStateRepository.CHARACTERS), fetcher);
    }

    /** One requested source and the gateway call that publishes it. */
    private record ScopedStep(String source, Step run) {}

    private interface Step {
        void run() throws Exception;
    }

    private void markRefreshed(String scope) {
        lastRefreshScope = scope;
        lastRefreshedAt = Instant.now();
    }

    public Instant lastRefreshedAt() { return lastRefreshedAt; }
    public String lastRefreshScope() { return lastRefreshScope; }
}
