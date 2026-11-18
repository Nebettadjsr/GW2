package application;

import model.AccountScope;
import sync.AccountRefreshGateway;
import sync.AccountSync;
import repo.AccountSyncStateRepository;
import java.util.List;
import java.util.Set;

/**
 * Application-layer use case for account synchronization, owning the distinctly scoped
 * account-refresh sequences:
 *
 * <ul>
 *   <li>{@link #refreshAll()} - account bank, account materials, account recipes, consumed account
 *       Luck, then every character's crafting ratings and recipes.</li>
 *   <li>{@link #refreshMaterialsAndRecipes()} - the narrower sequence of account materials then
 *       account recipes only, deliberately without the bank and character steps.</li>
 *   <li>{@link #refreshCraftingProfitData()} - the Crafting Profit inputs without Luck.</li>
 * </ul>
 *
 * <p>Performs no calculation; it only coordinates the {@link AccountRefreshGateway} collaborator.
 * In the unscoped sequences a failure from any step propagates immediately and short-circuits the
 * remaining steps. The account-scoped {@code refreshSources} variants instead attempt every
 * requested source independently.
 */
public class AccountRefreshService {

    private final AccountRefreshGateway gateway;

    public AccountRefreshService() {
        this(new AccountRefreshGateway());
    }

    /** Seam used by application-layer tests to substitute a fake gateway. */
    public AccountRefreshService(AccountRefreshGateway gateway) {
        this.gateway = gateway;
    }

    public void refreshAll() throws Exception {
        gateway.syncAccountBank();
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
        gateway.syncAccountLuck();
        gateway.syncCharacterCraftingAndRecipes();
    }

    public void refreshMaterialsAndRecipes() throws Exception {
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
    }

    /** Refreshes the account datasets consumed by Crafting Profit without the unrelated Luck API call. */
    public void refreshCraftingProfitData() throws Exception {
        gateway.syncAccountBank();
        gateway.syncAccountMaterials();
        gateway.syncAccountRecipes();
        gateway.syncCharacterCraftingAndRecipes();
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
     * Refreshes the requested sources for one account.
     *
     * <p>Unlike the unscoped {@link #refreshSources(Set)} above, a failing source does not
     * short-circuit the rest: each source is fetched and published independently, so one that
     * succeeds commits its own data, generation and completion even when another fails
     * (successful sources may commit even when another source fails).
     * The first failure is then thrown with the remaining ones attached, so nothing is swallowed and
     * no whole-account completion is fabricated from a partial run.
     *
     * <p>The fixed order is the one the unscoped sequences already used; it carries no dependency
     * between sources, since every publication is its own transaction.
     */
    public void refreshSources(AccountScope scope, Set<String> sources, AccountSync.AuthFetcher fetcher)
            throws Exception {
        refreshSources(scope, sources, fetcher, SourceOutcomes.NONE);
    }

    /** Receives each attempted source's own outcome; carries no failure detail. */
    public interface SourceOutcomes {
        SourceOutcomes NONE = new SourceOutcomes() {
            public void succeeded(String source) { }
            public void failed(String source) { }
        };

        void succeeded(String source);

        void failed(String source);
    }

    /** As above, additionally reporting which individual sources succeeded or failed. */
    public void refreshSources(AccountScope scope, Set<String> sources, AccountSync.AuthFetcher fetcher,
                               SourceOutcomes outcomes) throws Exception {
        List<ScopedStep> steps = List.of(
                new ScopedStep(AccountSyncStateRepository.BANK, () -> gateway.syncAccountBank(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.MATERIALS, () -> gateway.syncAccountMaterials(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.RECIPES, () -> gateway.syncAccountRecipes(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.LUCK, () -> gateway.syncAccountLuck(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.CHARACTERS,
                        () -> gateway.syncCharacterCraftingAndRecipes(scope, fetcher)),
                // Optional coverage: only ever run when explicitly demanded. refreshAll below lists its
                // sources explicitly and never includes it.
                new ScopedStep(AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS,
                        () -> gateway.syncCharacterEquipmentTabs(scope, fetcher)),
                new ScopedStep(AccountSyncStateRepository.CHARACTER_BUILD_TABS,
                        () -> gateway.syncCharacterBuildTabs(scope, fetcher)));

        Exception firstFailure = null;
        for (ScopedStep step : steps) {
            if (!sources.contains(step.source())) continue;
            try {
                step.run().run();
                outcomes.succeeded(step.source());
            } catch (Exception failure) {
                outcomes.failed(step.source());
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    /** Refreshes every account source this service knows, for one account. */
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
}
