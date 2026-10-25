package application;

import craft.CraftingSettings;
import model.AccountScope;
import repo.AccountSourceStateRepository;
import repo.DiscChoice;

import java.sql.SQLException;
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
 * Crafting Profit's stale-while-revalidate use case (TARGET_ARCHITECTURE.md §9.1, ADR-007).
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
    private final CalculationInputVersions.AccountSourceVersionReader accountVersions;
    private final CalculationInputVersions.AccountSourceStateReader accountSourceStates;
    private final CalculationInputVersions.PriceCoverageReader priceCoverage;
    private final AccountRefreshCoordinator accountRefresh;
    private final TradingPostRefreshCoordinator priceRefresh;
    private final BackgroundTasks tasks;
    private final Clock clock;
    private final Duration accountMaxAge;

    public CachedCraftingProfitService(ProfitInputSession.Factory sessions,
                                       Function<ProfitInputSession, CraftingProfitService> profitServices,
                                       CalculationInputVersions.AccountSourceVersionReader accountVersions,
                                       CalculationInputVersions.PriceCoverageReader priceCoverage,
                                       AccountRefreshCoordinator accountRefresh,
                                       TradingPostRefreshCoordinator priceRefresh,
                                       BackgroundTasks tasks,
                                       Clock clock,
                                       Duration accountMaxAge) {
        this(sessions, profitServices, accountVersions,
                new CalculationInputVersions.DatabaseAccountSourceStateReader(), priceCoverage,
                accountRefresh, priceRefresh, tasks, clock, accountMaxAge);
    }

    /**
     * The same use case with the account-scoped marker reader stated explicitly (STORY-APP-015):
     * {@link #profit(AccountScope, DiscChoice, CraftingSettings)} captures through it, while the
     * unscoped entry points keep using {@code accountVersions}.
     */
    public CachedCraftingProfitService(ProfitInputSession.Factory sessions,
                                       Function<ProfitInputSession, CraftingProfitService> profitServices,
                                       CalculationInputVersions.AccountSourceVersionReader accountVersions,
                                       CalculationInputVersions.AccountSourceStateReader accountSourceStates,
                                       CalculationInputVersions.PriceCoverageReader priceCoverage,
                                       AccountRefreshCoordinator accountRefresh,
                                       TradingPostRefreshCoordinator priceRefresh,
                                       BackgroundTasks tasks,
                                       Clock clock,
                                       Duration accountMaxAge) {
        this.sessions = sessions;
        this.profitServices = profitServices;
        this.accountVersions = accountVersions;
        this.accountSourceStates = accountSourceStates;
        this.priceCoverage = priceCoverage;
        this.accountRefresh = accountRefresh;
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

    public ProfitOutcome profit(DiscChoice choice, CraftingSettings settings) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingProfitService.ProfitData data = null;

        try (ProfitInputSession session = sessions.open()) {
            captured = capture(session);
            if (captured.usable()) {
                data = profitServices.apply(session).reload(choice, settings);
            }
        }

        return new ProfitOutcome(data, describe(requestId, captured, data != null));
    }

    public ResolutionOutcome resolution(int recipeId,
                                        DiscChoice choice,
                                        CraftingSettings settings,
                                        String basedOnCalculationId) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingResolutionDetail detail = null;

        try (ProfitInputSession session = sessions.open()) {
            captured = capture(session);
            if (captured.usable()) {
                detail = profitServices.apply(session).resolveDetail(recipeId, choice, settings);
            }
        }

        // The row in this response was recomputed from the same captured generation as its tree, so
        // the two are compatible by construction. What the caller cannot see for itself is whether
        // that generation is still the one its table row came from, which is what this reports.
        String consistency = basedOnCalculationId == null
                || basedOnCalculationId.equals(captured.calculationId())
                ? CONSISTENCY_FRESH_CALCULATION
                : CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS;

        return new ResolutionOutcome(detail, consistency, describe(requestId, captured, detail != null));
    }

    /**
     * One account's table calculation (STORY-APP-015), the scoped form of
     * {@link #profit(DiscChoice, CraftingSettings)}.
     *
     * <p>Same three steps in the same order, with the account carried through all of them: the
     * capture reads that account's markers and the calculation reads that account's rows, both from
     * the one snapshot {@code sessions.open(scope)} pinned, and the freshness envelope reports the
     * generations that capture actually saw rather than whatever has been published since.
     */
    public ProfitOutcome profit(AccountScope scope, DiscChoice choice, CraftingSettings settings) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingProfitService.ProfitData data = null;

        try (ProfitInputSession session = sessions.open(scope)) {
            captured = capture(session, scope);
            if (captured.usable()) {
                data = profitServices.apply(session).reload(choice, settings);
            }
        }

        return new ProfitOutcome(data, describeScoped(requestId, captured, data != null));
    }

    /**
     * One account's resolution detail (STORY-APP-015), the scoped form of
     * {@link #resolution(int, DiscChoice, CraftingSettings, String)}.
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
        String requestId = UUID.randomUUID().toString();
        CalculationInputVersions captured;
        CraftingResolutionDetail detail = null;

        try (ProfitInputSession session = sessions.open(scope)) {
            captured = capture(session, scope);
            if (captured.usable()) {
                detail = profitServices.apply(session).resolveDetail(recipeId, choice, settings);
            }
        }

        String consistency = basedOnCalculationId == null
                || basedOnCalculationId.equals(captured.calculationId())
                ? CONSISTENCY_FRESH_CALCULATION
                : CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS;

        return new ResolutionOutcome(detail, consistency, describeScoped(requestId, captured, detail != null));
    }

    private CalculationInputVersions capture(ProfitInputSession session) throws SQLException {
        Instant evaluatedAt = clock.instant();
        Map<String, Instant> versions = accountVersions.read(
                session.connection(), CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES);
        CalculationFreshness.PriceCoverage coverage = priceCoverage.read(session.connection());
        return new CalculationInputVersions(versions, coverage, accountMaxAge, evaluatedAt);
    }

    /** The scoped capture: this account's markers and generations, plus the shared quote coverage. */
    private CalculationInputVersions capture(ProfitInputSession session, AccountScope scope) throws SQLException {
        Instant evaluatedAt = clock.instant();
        Map<String, AccountSourceStateRepository.SourceState> states = accountSourceStates.read(
                session.connection(), scope, CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES);

        Map<String, Instant> versions = new LinkedHashMap<>();
        Map<String, Long> generations = new LinkedHashMap<>();
        states.forEach((source, state) -> {
            versions.put(source, state.completedAt());
            generations.put(source, state.generation());
        });

        CalculationFreshness.PriceCoverage coverage = priceCoverage.read(session.connection(), scope);
        return new CalculationInputVersions(scope.id(), versions, generations, coverage, accountMaxAge,
                evaluatedAt);
    }

    /**
     * The scoped envelope: describes the captured account and generations, and admits no refresh
     * work. A needed account refresh is reported as {@link #CREDENTIAL_REQUIRED}: a cached read
     * carries no GW2 key, so nothing is admitted and no configured credential is used. The existing coordinators run the unscoped sequences, so admitting through them from an
     * account-scoped calculation would silently invoke legacy work; the credential-aware scoped
     * coordinator belongs to issue #11. Outstanding needs are reported as a failure instead.
     */
    private CalculationFreshness describeScoped(String requestId,
                                                CalculationInputVersions captured,
                                                boolean resultAvailable) {
        List<CalculationFreshness.RefreshFailure> failures = new ArrayList<>();
        if (!captured.sourcesNeedingRefresh().isEmpty()) {
            failures.add(new CalculationFreshness.RefreshFailure(CalculationFreshness.RefreshKind.ACCOUNT,
                    null, CREDENTIAL_REQUIRED,
                    "Account sources " + captured.sourcesNeedingRefresh()
                            + " need a refresh; no GW2 key was supplied, so none was admitted", true));
        }
        if (!captured.coverage().covered()) {
            failures.add(new CalculationFreshness.RefreshFailure(CalculationFreshness.RefreshKind.TRADING_POST,
                    null, "SCOPED_REFRESH_NOT_ADMITTED",
                    "Trading Post quotes need a refresh, which scoped calculations do not start", true));
        }
        return new CalculationFreshness(
                requestId,
                captured.calculationId(),
                captured.evaluatedAt(),
                !resultAvailable || !captured.confirmedCurrent(),
                resultAvailable,
                captured.accountSources(),
                captured.coverage(),
                List.of(),
                List.copyOf(failures),
                captured.accountId(),
                captured.accountGenerations());
    }

    /**
     * Admits the work the capture showed to be outstanding and describes the captured inputs.
     *
     * <p>A capture whose required sources are all fresh and whose required item set is covered
     * admits nothing at all, which is what keeps a fresh page from triggering an upstream fetch.
     */
    private CalculationFreshness describe(String requestId,
                                          CalculationInputVersions captured,
                                          boolean resultAvailable) {
        List<CalculationFreshness.OutstandingRefresh> outstanding = new ArrayList<>();
        List<CalculationFreshness.RefreshFailure> failures = new ArrayList<>();

        Set<String> needing = captured.sourcesNeedingRefresh();
        if (!needing.isEmpty()) {
            AccountRefreshCoordinator.Admission admission = accountRefresh.require(needing);
            outstanding.add(new CalculationFreshness.OutstandingRefresh(
                    CalculationFreshness.RefreshKind.ACCOUNT,
                    admission.sources(),
                    admission.taskId(),
                    stateOf(admission.taskId()),
                    admission.reuse()));
            accountRefresh.lastFailure().ifPresent(failures::add);
            unknownTask(CalculationFreshness.RefreshKind.ACCOUNT, admission.taskId())
                    .ifPresent(failures::add);
        }

        if (!captured.coverage().covered()) {
            TradingPostRefreshCoordinator.Admission admission = priceRefresh.requireProfitCoverage();
            outstanding.add(new CalculationFreshness.OutstandingRefresh(
                    CalculationFreshness.RefreshKind.TRADING_POST,
                    Set.of(),
                    admission.taskId(),
                    stateOf(admission.taskId()),
                    admission.reuse()));
            priceRefresh.lastFailure().ifPresent(failures::add);
            unknownTask(CalculationFreshness.RefreshKind.TRADING_POST, admission.taskId())
                    .ifPresent(failures::add);
        }

        boolean provisional = !resultAvailable || !captured.confirmedCurrent();

        return new CalculationFreshness(
                requestId,
                captured.calculationId(),
                captured.evaluatedAt(),
                provisional,
                resultAvailable,
                captured.accountSources(),
                captured.coverage(),
                List.copyOf(outstanding),
                List.copyOf(failures));
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
