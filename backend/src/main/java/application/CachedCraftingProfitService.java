package application;

import craft.CraftingSettings;
import model.AccountScope;
import repo.AccountSourceStateRepository;
import repo.DiscChoice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Crafting Profit's stale-while-revalidate use case.
 *
 * <p>It replaces the two blocking paths the feature used to take — the account freshness gate that
 * refused the request with 503 while a refresh ran, and the synchronous Trading Post fetch inside
 * the request — with one ordering:
 *
 * <ol>
 *   <li><b>Capture.</b> Open one snapshot session and read the required account sources' completion
 *       markers and the required item set's quote coverage from it.</li>
 *   <li><b>Calculate.</b> If those captured inputs can produce a result, run the ordinary backend
 *       calculation on the same session, so every input comes from one published generation. Expired
 *       inputs are calculated from; a never-synchronized source is not invented.</li>
 *   <li><b>Coordinate.</b> Only then admit the refresh work the capture showed to be needed, and
 *       describe the captured inputs in the freshness envelope. Nothing in this step can change what
 *       was calculated or advance a completion marker, and nothing waits for upstream work.</li>
 * </ol>
 *
 * <p>Admitting after calculating is deliberate: the envelope must describe the inputs the result
 * came from rather than newer markers, and the account writer then never runs while this request's
 * read snapshot is open.
 *
 * <p>Every call is account-scoped; there is no unscoped calculation path.
 *
 * <p>The domain is untouched by all of this. It still receives ordinary calculation inputs and has
 * no clock, task handle or freshness metadata; profit mathematics, inventory ownership, fees and
 * quantities are exactly those of {@link CraftingProfitService}.
 */
public class CachedCraftingProfitService {

    /** Default account TTL, matching the configured HTTP freshness policy. */
    public static final Duration DEFAULT_ACCOUNT_MAX_AGE = Duration.ofMinutes(15);

    /** A cached read that needs a refresh but carries no GW2 key: reported, never admitted (STORY-API-011). */
    public static final String CREDENTIAL_REQUIRED = "CREDENTIAL_REQUIRED";

    /** The detail explains the same input generation the caller's row was computed from. */
    public static final String CONSISTENCY_FRESH_CALCULATION = "FRESH_CALCULATION";

    /**
     * The inputs changed since the caller's row: this response is a complete recalculation, and its
     * own {@code row} — not the caller's older one — is what its tree explains.
     */
    public static final String CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS =
            "RECALCULATED_FOR_CHANGED_INPUTS";

    private final ProfitInputSession.Factory sessions;
    private final Function<ProfitInputSession, CraftingProfitService> profitServices;
    private final CalculationInputVersions.AccountSourceStateReader accountSourceStates;
    private final CalculationInputVersions.PriceCoverageReader priceCoverage;
    private final TradingPostRefreshCoordinator priceRefresh;
    private final BackgroundTasks tasks;
    private final Clock clock;
    private final Duration accountMaxAge;
    private ScopedAccountRefreshCoordinator scopedRefresh;
    private CharacterRoster roster;

    /** The names of the characters one account owns, read without recording activity. */
    @FunctionalInterface
    public interface CharacterRoster {
        List<String> namesOf(AccountScope scope) throws DataStoreException;
    }

    /** The selected character is not on the authorized account's roster; nothing was calculated. */
    public static final class CharacterNotOwnedException extends RuntimeException {
        public CharacterNotOwnedException() {
            super("selected character is not on the authorized account");
        }
    }

    /** Binds the roster lookup a character-scoped request is validated against before any work. */
    public CachedCraftingProfitService withCharacterRoster(CharacterRoster roster) {
        this.roster = roster;
        return this;
    }

    /**
     * A selected character must be on the account's published roster before activity, capture or
     * calculation. An account whose roster was never published has nothing to compare with; that
     * stays the pending (no-result) case rather than a rejection.
     */
    private void verifyCharacter(AccountScope scope, DiscChoice choice) throws DataStoreException {
        if (roster == null || choice == null || choice.kind != DiscChoice.Kind.CHAR_DISCIPLINE) return;
        List<String> owned = roster.namesOf(scope);
        if (!owned.isEmpty() && !owned.contains(choice.charName)) throw new CharacterNotOwnedException();
    }

    /**
     * Binds the credential-aware coordinator keyed requests admit through (STORY-API-011). Without
     * it, a request that carries a key is treated exactly like one that carries none.
     */
    public CachedCraftingProfitService withScopedRefresh(ScopedAccountRefreshCoordinator coordinator) {
        this.scopedRefresh = coordinator;
        return this;
    }

    /**
     * The caller's transient proof for a keyed calculation: the trusted context, the presented
     * session and the key. Never stored; handed only to {@link ScopedAccountRefreshCoordinator}.
     */
    public record KeyedAdmission(application.access.AccountContext context, String sessionId, String apiKey,
                                 java.util.concurrent.atomic.AtomicReference<application.access.AccountAccessService.KeyedProof> proof) {
        /** {@code proof} is request-local: filled by the preflight, reused by admission of the same request. */
        public KeyedAdmission(application.access.AccountContext context, String sessionId, String apiKey) {
            this(context, sessionId, apiKey, new java.util.concurrent.atomic.AtomicReference<>());
        }

        @Override public String toString() { return "KeyedAdmission[context=" + context + ", apiKey=<redacted>]"; }
    }

    /**
     * The use case over one account-scoped snapshot per call: the account's markers are captured
     * through {@code accountSourceStates} and the shared quote coverage through {@code priceCoverage}.
     */
    public CachedCraftingProfitService(ProfitInputSession.Factory sessions,
                                       Function<ProfitInputSession, CraftingProfitService> profitServices,
                                       CalculationInputVersions.AccountSourceStateReader accountSourceStates,
                                       CalculationInputVersions.PriceCoverageReader priceCoverage,
                                       TradingPostRefreshCoordinator priceRefresh,
                                       BackgroundTasks tasks,
                                       Clock clock,
                                       Duration accountMaxAge) {
        this.sessions = sessions;
        this.profitServices = profitServices;
        this.accountSourceStates = accountSourceStates;
        this.priceCoverage = priceCoverage;
        this.priceRefresh = priceRefresh;
        this.tasks = tasks;
        this.clock = clock;
        this.accountMaxAge = accountMaxAge;
    }

    /**
     * One table calculation and the freshness of what it used.
     *
     * @param data null when no usable cached inputs existed; never a fabricated empty result
     */
    public record ProfitOutcome(CraftingProfitService.ProfitData data, CalculationFreshness freshness) {

        public boolean hasResult() {
            return data != null;
        }
    }

    /**
     * One recipe's resolution detail, the row it explains, and the freshness of what both used.
     *
     * @param detail null when no usable cached inputs existed
     */
    public record ResolutionOutcome(CraftingResolutionDetail detail,
                                    String consistency,
                                    CalculationFreshness freshness) {

        public boolean hasResult() {
            return detail != null;
        }
    }

    /**
     * One account's table calculation (STORY-APP-015).
     *
     * <p>Three steps in the same order, with the account carried through all of them: the
     * capture reads that account's markers and the calculation reads that account's rows, both from
     * the one snapshot {@code sessions.open(scope)} pinned, and the freshness envelope reports the
     * generations that capture actually saw rather than whatever has been published since.
     */
    public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings) throws Exception {
        return profit(scope, choice, settings, null);
    }

    public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings,
                                KeyedAdmission keyed) throws Exception {
        return profit(scope, choice, settings, keyed, keyed == null ? null : keyed.context());
    }

    /**
     * @param readContext the already validated request context, independent of optional keyed refresh
     *                    admission: it alone decides whether the cached wallet may be read (grant and endpoint).
     */
    public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings,
                                KeyedAdmission keyed, application.access.AccountContext readContext) throws Exception {
        settings = CraftingSettings.ordinaryOnly(settings);
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingProfitService.ProfitData data = null;

        verifyProof(keyed);
        verifyCharacter(scope, choice);
        boolean wallet = walletAuthorized(readContext, keyed);
        try (ProfitInputSession session = sessions.open(scope).authorizeWallet(wallet)) {
            captured = capture(session, scope, wallet, settings);
            if (captured.usable()) {
                data = profitServices.apply(session).reload(choice, settings);
            }
        }

        return new ProfitOutcome(data, describe(requestId, captured, data != null, scope, keyed));
    }

    /** One account's resolution detail (STORY-APP-015).
     *
     * <p>The consistency verdict is scoped by construction: the captured identity includes the
     * account, so a calculation id established for a different account can never read as
     * {@code FRESH_CALCULATION} here.
     */
    public ResolutionOutcome resolution(AccountScope scope,
                                        int recipeId,
                                        DiscChoice choice,
                                        CraftingSettings settings,
                                        String basedOnCalculationId) throws Exception {
        return resolution(scope, recipeId, choice, settings, basedOnCalculationId, null);
    }

    public ResolutionOutcome resolution(AccountScope scope,
                                        int recipeId,
                                        DiscChoice choice,
                                        CraftingSettings settings,
                                        String basedOnCalculationId,
                                        KeyedAdmission keyed) throws Exception {
        return resolution(scope, recipeId, choice, settings, basedOnCalculationId, keyed,
                keyed == null ? null : keyed.context());
    }

    public ResolutionOutcome resolution(AccountScope scope,
                                        int recipeId,
                                        DiscChoice choice,
                                        CraftingSettings settings,
                                        String basedOnCalculationId,
                                        KeyedAdmission keyed,
                                        application.access.AccountContext readContext) throws Exception {
        settings = CraftingSettings.ordinaryOnly(settings);
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingResolutionDetail detail = null;

        verifyProof(keyed);
        verifyCharacter(scope, choice);
        boolean wallet = walletAuthorized(readContext, keyed);
        try (ProfitInputSession session = sessions.open(scope).authorizeWallet(wallet)) {
            captured = capture(session, scope, wallet, settings);
            if (captured.usable()) {
                detail = profitServices.apply(session).resolveDetail(recipeId, choice, settings);
            }
        }

        String consistency = basedOnCalculationId == null
                || basedOnCalculationId.equals(captured.calculationId())
                ? CONSISTENCY_FRESH_CALCULATION
                : CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS;

        return new ResolutionOutcome(detail, consistency, describe(requestId, captured, detail != null, scope, keyed));
    }

    /** One selected Forge project over one account snapshot; {@code data} is null when no usable cached inputs existed. */
    public record ForgeProjectOutcome(CraftingProfitService.ForgeProjectData data, boolean unknownRecipe,
                                      CalculationFreshness freshness) {
        public boolean hasResult() { return data != null; }
    }

    /**
     * Exact planning of one Forge project in one account-scoped snapshot. Same freshness/admission contract as
     * {@link #profit}: a keyless request is a pure cached read and admits no account work.
     */
    public ForgeProjectOutcome forgeProject(AccountScope scope, int recipeId, long target, KeyedAdmission keyed,
                                            application.access.AccountContext readContext) throws Exception {
        return forgeProject(scope, recipeId, target, craft.ForgeSettings.DEFAULT, keyed, readContext);
    }

    public ForgeProjectOutcome forgeProject(AccountScope scope, int recipeId, long target, craft.ForgeSettings settings,
                                            KeyedAdmission keyed, application.access.AccountContext readContext) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingProfitService.ForgeProjectData data = null;
        // An unknown recipe is a structural fact, independent of account data: reject before proof, capture, refresh
        // admission or planning (forgeProjectItemIds returns null before any graph/price read for such an id).
        try (ProfitInputSession probe = sessions.open(scope)) {
            if (profitServices.apply(probe).forgeProjectItemIds(recipeId) == null) {
                return new ForgeProjectOutcome(null, true, null);
            }
        }
        verifyProof(keyed);
        boolean wallet = walletAuthorized(readContext, keyed);
        try (ProfitInputSession session = sessions.open(scope).authorizeWallet(wallet)) {
            CraftingProfitService service = profitServices.apply(session);
            // Source identity and price demand are this project's own public path, never the ordinary set or the
            // whole catalogue.
            java.util.Set<Integer> pathIds = service.forgeProjectPriceIds(recipeId, settings);
            if (pathIds == null) pathIds = java.util.Set.of();
            captured = capture(session, scope, wallet, null, recipeId, pathIds, settings);
            if (captured.usable()) data = service.planForgeProject(recipeId, target, settings);
        }
        return new ForgeProjectOutcome(data, false, describe(requestId, captured, data != null, scope, keyed));
    }

    /** Validated session authority; a presented key must additionally have proved its own wallet scope. */
    private static boolean walletAuthorized(application.access.AccountContext context, KeyedAdmission keyed) {
        if (context == null || !context.permits(application.access.AccountOperation.WALLET_READ)) return false;
        if (keyed == null) return true;
        var proof = keyed.proof().get();
        return proof != null && proof.credentialScope() != null
                && proof.credentialScope().permits(application.access.AccountOperation.WALLET_READ);
    }

    /**
     * A presented key is proven before any account work or activity: a definitive rejection
     * ({@link ScopedAccountRefreshCoordinator.Rejected}) reaches the caller instead of a calculation.
     */
    private void verifyProof(KeyedAdmission keyed) {
        if (keyed == null || scopedRefresh == null) return;
        keyed.proof().set(scopedRefresh.verify(keyed.context(), keyed.sessionId(), keyed.apiKey(),
                CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES));
    }

    /** The scoped capture: this account's markers and generations, plus the shared quote coverage. */
    private CalculationInputVersions capture(ProfitInputSession session, AccountScope scope, boolean wallet,
                                             CraftingSettings settings) throws DataStoreException {
        return capture(session, scope, wallet, settings, null, null, null);
    }

    /** {@code projectPathIds} non-null = a Forge project source: price coverage/identity over exactly those ids. */
    private CalculationInputVersions capture(ProfitInputSession session, AccountScope scope, boolean wallet,
                                             CraftingSettings settings, Integer projectRecipeId,
                                             java.util.Set<Integer> projectPathIds, craft.ForgeSettings forgeSettings)
            throws DataStoreException {
        return DataStoreException.translate(() -> readCapture(session, scope, wallet, settings, projectRecipeId,
                projectPathIds, forgeSettings));
    }

    private CalculationInputVersions readCapture(ProfitInputSession session, AccountScope scope, boolean wallet,
                                                 CraftingSettings settings, Integer projectRecipeId,
                                                 java.util.Set<Integer> projectPathIds,
                                                 craft.ForgeSettings forgeSettings) throws Exception {
        Instant evaluatedAt = clock.instant();
        session.withWalletFreshness(evaluatedAt, accountMaxAge);
        Set<String> sources = new java.util.LinkedHashSet<>(CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES);
        if (wallet) sources.add(repo.AccountSyncStateRepository.WALLET);
        Map<String, AccountSourceStateRepository.SourceState> states = accountSourceStates.read(
                session.connection(), scope, sources);

        Map<String, Instant> versions = new LinkedHashMap<>();
        Map<String, Long> generations = new LinkedHashMap<>();
        states.forEach((source, state) -> {
            versions.put(source, state.completedAt());
            generations.put(source, state.generation());
        });

        if (projectPathIds != null) {
            CalculationFreshness.PriceCoverage coverage =
                    priceCoverage.readExplicit(session.connection(), scope, projectPathIds);
            String identity = "project=" + projectRecipeId + "|" + parser.MysticForgeCatalogLoader.bundled().fingerprint()
                    + "|" + craft.VendorAcquisitions.fingerprint() + "|" + craft.ForgeReferenceRecipes.fingerprint()
                    + "|ids=" + projectPathIds + (forgeSettings == null ? "" : "|" + forgeSettings.fingerprint());
            boolean metadataGap = priceCoverage.metadataIncomplete(session.connection(), projectPathIds);
            return new CalculationInputVersions(scope.id(), versions, generations, coverage, accountMaxAge,
                    evaluatedAt).withWalletTracked(wallet).withProject(identity, projectPathIds)
                    .withProjectMetadataIncomplete(metadataGap);
        }
        CalculationFreshness.PriceCoverage coverage = priceCoverage.read(session.connection(), scope);
        return new CalculationInputVersions(scope.id(), versions, generations, coverage, accountMaxAge,
                evaluatedAt).withWalletTracked(wallet);
    }

    /**
     * The scoped envelope: describes the captured account and generations. Needed account refreshes
     * are admitted only through the credential-aware {@link ScopedAccountRefreshCoordinator} for a
     * keyed request; a cached read with no key admits nothing, uses no configured credential and
     * reports {@link #CREDENTIAL_REQUIRED} instead. Shared Trading Post quotes are account-free and
     * need no key, so their refresh is admitted without one.
     */
    private CalculationFreshness describe(String requestId,
                                                CalculationInputVersions captured,
                                                boolean resultAvailable,
                                                AccountScope scope,
                                                KeyedAdmission keyed) {
        List<CalculationFreshness.RefreshFailure> failures = new ArrayList<>();
        List<CalculationFreshness.OutstandingRefresh> outstanding = new ArrayList<>();
        Set<String> needing = captured.sourcesNeedingRefresh();
        if (!needing.isEmpty()) {
            if (keyed == null || scopedRefresh == null) {
                failures.add(new CalculationFreshness.RefreshFailure(CalculationFreshness.RefreshKind.ACCOUNT,
                        null, CREDENTIAL_REQUIRED,
                        "Account sources " + needing
                                + " need a refresh; no GW2 key was supplied, so none was admitted", true));
            } else {
                // Proof was verified before any work; a late rejection (revocation or outage during
                // the calculation) propagates as API-010 401/403/503 rather than a calculation.
                ScopedAccountRefreshCoordinator.Admission admission = scopedRefresh.require(
                        keyed.context(), keyed.sessionId(), keyed.apiKey(), needing, keyed.proof().get());
                outstanding.add(new CalculationFreshness.OutstandingRefresh(
                        CalculationFreshness.RefreshKind.ACCOUNT, admission.sources(), admission.taskId(),
                        stateOf(admission.taskId()), admission.reuse()));
            }
        }
        if (!captured.coverage().covered() || captured.projectMetadataIncomplete()) {
            // A selected project also needs admission when its ingredient metadata is incomplete although quotes are fresh.
            // Trading Post quotes are shared, account-free data and need no key, so the on-demand
            // shared refresh is admitted without a key.
            // A project demands exactly its own path ids on the shared single-flight run; ordinary calculations
            // keep the ordinary scoped demand.
            TradingPostRefreshCoordinator.Admission admission = captured.forgeEnabled()
                    ? priceRefresh.requireItems(captured.projectPriceIds(), scope)
                    : priceRefresh.requireProfitCoverage(scope);
            outstanding.add(new CalculationFreshness.OutstandingRefresh(
                    CalculationFreshness.RefreshKind.TRADING_POST, Set.of(), admission.taskId(),
                    stateOf(admission.taskId()), admission.reuse()));
            priceRefresh.lastFailure().ifPresent(failures::add);
            unknownTask(CalculationFreshness.RefreshKind.TRADING_POST, admission.taskId())
                    .ifPresent(failures::add);
        }
        return new CalculationFreshness(
                requestId,
                captured.calculationId(),
                captured.evaluatedAt(),
                !resultAvailable || !captured.confirmedCurrent(),
                resultAvailable,
                captured.accountSources(),
                captured.coverage(),
                List.copyOf(outstanding),
                List.copyOf(failures),
                captured.accountId(),
                captured.accountGenerations());
    }

    private String stateOf(String taskId) {
        if (taskId == null) return "NONE";
        return tasks.find(taskId).map(BackgroundTasks.Status::state).orElse("UNKNOWN");
    }

    /**
     * A task identifier this process can no longer resolve — evicted, or lost to a restart — is
     * reported as a bounded, actionable recovery item rather than left for the client to poll
     * forever.
     */
    private Optional<CalculationFreshness.RefreshFailure> unknownTask(
            CalculationFreshness.RefreshKind kind, String taskId) {
        if (taskId == null || tasks.find(taskId).isPresent()) return Optional.empty();
        return Optional.of(new CalculationFreshness.RefreshFailure(kind, taskId, "TASK_UNKNOWN",
                "The refresh task is no longer known to this backend; request the calculation again to restart it",
                true));
    }
}
