package application;

import application.access.AccountContext;
import application.access.AccountGrant;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import craft.SingleCraftExplanation;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import repo.DiscChoice;
import sync.TradingPostPriceRefreshGateway;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyed account-refresh guarantees of the REAL {@link CachedCraftingProfitService} (STORY-CLEAN-032):
 * it is bound with {@code withScopedRefresh} and called with a {@code KeyedAdmission}, so the keyed
 * branch of capture/describe runs. Only the credential/grant coordinator is a recording port double;
 * {@code ScopedAccountRefreshCoordinatorTest} owns credential and grant details.
 */
class CachedCraftingProfitServiceKeyedTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant FRESH_AT = NOW.minus(Duration.ofMinutes(3));
    private static final Instant EXPIRED_AT = NOW.minus(Duration.ofHours(4));
    private static final Recipe RECIPE = new Recipe(7, 100, 2, 275, "Artificer", List.of());
    private static final CraftResult RESULT = new CraftResult(
            100, "Artificer", 4, Map.of(), Map.of(), 1_234, 56, 7_890, 700, 2_800, null);
    private static final String SESSION = "session-1";
    private static final String KEY = "TEST-KEY-NOT-REAL";

    private final List<String> events = new ArrayList<>();
    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final List<Set<String>> demanded = new ArrayList<>();
    private final List<AccountContext> demandContexts = new ArrayList<>();
    private final List<String> demandSessions = new ArrayList<>();

    private Map<String, Instant> completed = new LinkedHashMap<>();
    private Instant defaultCompletedAt = FRESH_AT;
    private boolean covered = true;
    private final Set<String> missing = new java.util.HashSet<>();
    private String heldTaskId;

    private final TradingPostPriceRefreshGateway noQuotes = new TradingPostPriceRefreshGateway() {
        @Override public void syncTpPricesForProfit() { }
        @Override public void syncTpPricesForProfit(AccountScope scope) { }
    };

    /** Records what the service hands the credential-aware coordinator; admits against a held task. */
    private final class RecordingCoordinator extends ScopedAccountRefreshCoordinator {
        RecordingCoordinator() { super(null, tasks, null, null); }

        @Override
        public application.access.AccountAccessService.KeyedProof verify(AccountContext context, String sessionId,
                                                                          String apiKey, Set<String> sources) {
            events.add("verify");
            return new application.access.AccountAccessService.KeyedProof(context);
        }

        @Override
        public Admission require(AccountContext context, String sessionId, String apiKey, Set<String> sources,
                                 application.access.AccountAccessService.KeyedProof proof) {
            events.add("require");
            demanded.add(Set.copyOf(sources));
            demandContexts.add(context);
            demandSessions.add(sessionId);
            return new Admission(heldTaskId, sources, REUSE_ADMITTED);
        }
    }

    private static AccountContext context(AccountScope scope) {
        return new AccountContext(scope.id().toString(), Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
                AccountGrant.CHARACTERS, AccountGrant.UNLOCKS), Set.of(), "test", NOW.plus(Duration.ofHours(1)));
    }

    private ProfitInputSession recordingSession() {
        Connection con = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) events.add("close");
                    if (method.getName().equals("toString")) return "recording-connection";
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        try {
            Constructor<ProfitInputSession> ctor = ProfitInputSession.class.getDeclaredConstructor(Connection.class);
            ctor.setAccessible(true);
            return ctor.newInstance(con);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                scope -> {
                    events.add("open");
                    return recordingSession();
                },
                session -> new CraftingProfitService(null, null, null, null, null, null, null, session) {
                    @Override public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
                        events.add("calculate");
                        return new ProfitData(List.of(RECIPE), List.of(RECIPE),
                                Map.of(RECIPE.recipeId, RESULT), Map.of(), Map.of());
                    }
                    @Override public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                                            CraftingSettings settings) {
                        events.add("calculate");
                        return new CraftingResolutionDetail(RECIPE.recipeId,
                                CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, RECIPE, RESULT,
                                SingleCraftExplanation.unavailable(RECIPE.recipeId, RECIPE.outputItemId,
                                        RECIPE.outputCount), Map.of(), Map.of());
                    }
                },
                (con, scope, sources) -> {
                    events.add("read");
                    Map<String, AccountSourceStateRepository.SourceState> states = new LinkedHashMap<>();
                    for (String s : sources) {
                        if (missing.contains(s)) continue; // the real reader omits a never-published source
                        states.put(s, new AccountSourceStateRepository.SourceState(s, 5L,
                                completed.getOrDefault(s, defaultCompletedAt)));
                    }
                    return states;
                },
                con -> new CalculationFreshness.PriceCoverage(
                        10, covered ? 10 : 0, covered ? 0 : 10, 0, FRESH_AT, FRESH_AT, covered, "q"),
                new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(noQuotes), tasks),
                tasks,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15)).withScopedRefresh(new RecordingCoordinator());
    }

    private CachedCraftingProfitService.KeyedAdmission keyed(AccountScope scope) {
        return new CachedCraftingProfitService.KeyedAdmission(context(scope), SESSION, KEY);
    }

    @Test
    void staleSourcesAreDemandedForTheRequestingAccountAndReportedAsOutstandingWithoutWaiting() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());
        // Only bank and recipes are expired; every other required source is current.
        completed.put(AccountSyncStateRepository.BANK, EXPIRED_AT);
        completed.put(AccountSyncStateRepository.RECIPES, EXPIRED_AT);
        heldTaskId = tasks.admitForeign(ScopedAccountRefreshCoordinator.OPERATION); // a refresh that never finishes

        CachedCraftingProfitService.ProfitOutcome outcome =
                service().profit(scope, DiscChoice.all(), settings(), keyed(scope));

        // The held refresh did not block: the ordinary result came back, provisional, with the work named.
        assertTrue(outcome.hasResult());
        assertEquals(RESULT, outcome.data().resultsByRecipeId().get(RECIPE.recipeId));
        CalculationFreshness freshness = outcome.freshness();
        assertTrue(freshness.provisional());
        assertTrue(freshness.failures().isEmpty(), "a keyed request is not credential-required");
        assertEquals(1, freshness.outstandingWork().size());
        var work = freshness.outstandingWork().get(0);
        assertEquals(CalculationFreshness.RefreshKind.ACCOUNT, work.kind());
        assertEquals(Set.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.RECIPES), work.sources());
        assertEquals(heldTaskId, work.taskId());
        assertEquals("RUNNING", work.taskState());

        // The coordinator received exactly the stale sources, for this account and this session.
        assertEquals(List.of(Set.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.RECIPES)), demanded);
        assertEquals(List.of(scope.id().toString()), demandContexts.stream().map(AccountContext::accountId).toList());
        assertEquals(List.of(SESSION), demandSessions);
        assertEquals(scope.id(), freshness.accountId());
    }

    @Test
    void missingSourceMarkerIsDemandedAndTheResultIsPending() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());
        missing.add(AccountSyncStateRepository.MATERIALS);
        heldTaskId = tasks.admitForeign(ScopedAccountRefreshCoordinator.OPERATION);

        CachedCraftingProfitService.ProfitOutcome outcome =
                service().profit(scope, DiscChoice.all(), settings(), keyed(scope));

        assertEquals(List.of(Set.of(AccountSyncStateRepository.MATERIALS)), demanded, "a never-completed source is demanded, and only it");
        assertTrue(outcome.freshness().provisional());
        assertEquals(Set.of(AccountSyncStateRepository.MATERIALS), outcome.freshness().outstandingWork().get(0).sources());
        assertFalse(outcome.hasResult(), "no usable input yet: the pending response carries no result");
        assertFalse(events.contains("calculate"), "nothing is calculated from an absent source");
        assertEquals("RUNNING", outcome.freshness().outstandingWork().get(0).taskState(),
                "the response names the held refresh instead of waiting for it");
    }

    @Test
    void currentSourcesDemandNothingAndAreFinal() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());

        CalculationFreshness freshness = service().profit(scope, DiscChoice.all(), settings(), keyed(scope)).freshness();

        assertTrue(demanded.isEmpty());
        assertFalse(freshness.provisional());
        assertTrue(freshness.outstandingWork().isEmpty());
        assertTrue(freshness.failures().isEmpty());
        assertEquals(List.of("verify", "open", "read", "calculate", "close"), events);
    }

    @Test
    void proofIsVerifiedBeforeTheSnapshotAndAdmissionOccursOnlyAfterItClosed() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());
        completed.put(AccountSyncStateRepository.BANK, EXPIRED_AT);
        heldTaskId = tasks.admitForeign(ScopedAccountRefreshCoordinator.OPERATION);

        service().profit(scope, DiscChoice.all(), settings(), keyed(scope));

        assertEquals(List.of("verify", "open", "read", "calculate", "close", "require"), events,
                "verify before the snapshot opens or any input is read; calculate inside it; admit only after it closed");
    }

    @Test
    void resolutionAdmitsThroughTheSameKeyedPathAfterItsSnapshotCloses() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());
        completed.put(AccountSyncStateRepository.RECIPES, EXPIRED_AT);
        heldTaskId = tasks.admitForeign(ScopedAccountRefreshCoordinator.OPERATION);

        var outcome = service().resolution(scope, RECIPE.recipeId, DiscChoice.all(), settings(), null, keyed(scope));

        assertEquals(List.of(Set.of(AccountSyncStateRepository.RECIPES)), demanded);
        assertEquals(List.of("verify", "open", "read", "calculate", "close", "require"), events);
        assertTrue(outcome.freshness().provisional());
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }
}
