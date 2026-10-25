package application;

import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import repo.DiscChoice;
import sync.AccountRefreshGateway;
import sync.TradingPostPriceRefreshGateway;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criteria 1, 2, 3 and 4: the cached-result contract for Crafting Profit.
 *
 * <p>The matrix below covers fresh inputs, expired account inputs, expired Trading Post quotes, both
 * expired, a successfully synchronized but empty source and a source that never synchronized. The
 * clock is fixed, the input versions are stated by the test rather than read from a database, and
 * the refresh gateways are held closed on a latch — so when a case asserts that the ordinary result
 * came back, it is asserting that it came back <em>without</em> upstream work having been allowed to
 * finish, which is the whole point of the contract.
 */
class CachedCraftingProfitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final Instant FRESH_AT = NOW.minus(Duration.ofMinutes(3));
    private static final Instant EXPIRED_AT = NOW.minus(Duration.ofHours(4));
    private static final Duration MAX_AGE = Duration.ofMinutes(15);

    private static final Recipe RECIPE = new Recipe(7, 100, 2, 275, "Artificer", List.of());
    private static final CraftResult RESULT = new CraftResult(
            100, "Artificer", 4, Map.of(), Map.of(), 1_234, 56, 7_890, 700, 2_800, null);

    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final HeldAccountGateway accountGateway = new HeldAccountGateway();
    private final HeldPriceGateway priceGateway = new HeldPriceGateway();
    private final AccountRefreshCoordinator accountRefresh =
            new AccountRefreshCoordinator(new AccountRefreshService(accountGateway), tasks);
    private final TradingPostRefreshCoordinator priceRefresh =
            new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(priceGateway), tasks);

    private final Map<String, Instant> versions = new LinkedHashMap<>();
    private final StubProfitServices profitServices = new StubProfitServices();
    private CalculationFreshness.PriceCoverage coverage = covered();

    // ---------- the freshness matrix ----------

    @Test
    void freshInputsProduceAFinalResultAndAdmitNoRefreshAtAll() throws Exception {
        allSourcesAt(FRESH_AT);

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult());
        assertEquals(1, outcome.data().visibleRecipes().size());
        assertFalse(outcome.freshness().provisional(), "confirmed-current inputs make the result final");
        assertTrue(outcome.freshness().resultAvailable());
        assertEquals(List.of(), outcome.freshness().outstandingWork());
        assertEquals(List.of(), tasks.operations(), "a fresh page must trigger no upstream fetch");
        for (CalculationFreshness.SourceFreshness source : outcome.freshness().accountSources()) {
            assertEquals(CalculationFreshness.SourceState.FRESH, source.state(), source.source());
            assertEquals(FRESH_AT, source.fetchedAt(), source.source());
        }
    }

    @Test
    void expiredAccountInputsStillReturnTheOrdinaryResultBeforeAnyRefreshCompletes() throws Exception {
        allSourcesAt(EXPIRED_AT);
        accountGateway.hold();
        tasks.autoRun = true;

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult(), "expiry is not loss of the last successful data");
        assertEquals(4, outcome.data().resultsByRecipeId().get(7).craftableCount,
                "the economic result is the ordinary one, not a degraded placeholder");
        assertTrue(outcome.freshness().provisional());

        CalculationFreshness.OutstandingRefresh work = only(outcome.freshness().outstandingWork());
        accountGateway.awaitEntered();
        assertTrue(accountGateway.stillInFlight(),
                "the result was answered while the refresh was still running");
        assertEquals("RUNNING", tasks.stateOf(work.taskId()));

        assertEquals(CalculationFreshness.RefreshKind.ACCOUNT, work.kind());
        assertEquals(CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES, work.sources());
        assertEquals(AccountRefreshCoordinator.REUSE_ADMITTED, work.reuse());
        for (CalculationFreshness.SourceFreshness source : outcome.freshness().accountSources()) {
            assertEquals(CalculationFreshness.SourceState.STALE, source.state(), source.source());
            assertEquals(EXPIRED_AT, source.fetchedAt(), source.source());
        }

        accountGateway.release();
        tasks.joinAll();
    }

    @Test
    void expiredPricesStillReturnTheOrdinaryResultAndAdmitOnlyTradingPostWork() throws Exception {
        allSourcesAt(FRESH_AT);
        coverage = new CalculationFreshness.PriceCoverage(
                2_984, 2_000, 900, 84, EXPIRED_AT, FRESH_AT, false, "quotes-expired");
        priceGateway.hold();
        tasks.autoRun = true;

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult());
        assertTrue(outcome.freshness().provisional());
        priceGateway.awaitEntered();
        assertTrue(priceGateway.stillInFlight(),
                "the result was answered while the quote fetch was still running");

        CalculationFreshness.OutstandingRefresh work = only(outcome.freshness().outstandingWork());
        assertEquals(CalculationFreshness.RefreshKind.TRADING_POST, work.kind());
        assertEquals(Set.of(), work.sources(), "TP work recomputes its own item set");
        assertEquals(List.of(TradingPostRefreshCoordinator.PROFIT_OPERATION), tasks.operations(),
                "fresh account sources must not be refreshed because prices expired");

        CalculationFreshness.PriceCoverage reported = outcome.freshness().tradingPost();
        assertFalse(reported.covered());
        assertEquals(2_984, reported.requiredItems());
        assertEquals(84, reported.missingItems(),
                "items with no stored quote are uncovered, never silently treated as answered");

        priceGateway.release();
        tasks.joinAll();
    }

    @Test
    void bothExpiredAdmitTwoSeparatelyIdentifiedPiecesOfWork() throws Exception {
        allSourcesAt(EXPIRED_AT);
        coverage = uncovered();
        accountGateway.hold();
        priceGateway.hold();
        tasks.autoRun = true;

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult());
        assertTrue(outcome.freshness().provisional());
        accountGateway.awaitEntered();
        priceGateway.awaitEntered();
        assertTrue(accountGateway.stillInFlight() && priceGateway.stillInFlight(),
                "neither mechanism may have to finish before the cached result is answered");
        assertEquals(2, outcome.freshness().outstandingWork().size());
        assertEquals(List.of(CalculationFreshness.RefreshKind.ACCOUNT,
                        CalculationFreshness.RefreshKind.TRADING_POST),
                outcome.freshness().outstandingWork().stream()
                        .map(CalculationFreshness.OutstandingRefresh::kind).toList(),
                "account and price state stay distinguishable");
        assertEquals(List.of(AccountRefreshCoordinator.OPERATION,
                        TradingPostRefreshCoordinator.PROFIT_OPERATION), tasks.operations(),
                "two independent operations, not one merged refresh");

        accountGateway.release();
        priceGateway.release();
        tasks.joinAll();
    }

    @Test
    void aSuccessfullySynchronizedEmptySourceIsUsable() throws Exception {
        allSourcesAt(FRESH_AT);
        profitServices.canned = new CraftingProfitService.ProfitData(
                List.of(), List.of(), Map.of(), Map.of(), Map.of());

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult(), "synchronized emptiness is an answer, not an absent source");
        assertEquals(0, outcome.data().visibleRecipes().size());
        assertFalse(outcome.freshness().provisional());
        assertEquals(List.of(), tasks.operations());
    }

    @Test
    void aSourceThatNeverSynchronizedProducesAPendingStateAndNoFabricatedResult() throws Exception {
        allSourcesAt(FRESH_AT);
        versions.remove(AccountSyncStateRepository.BANK);

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertFalse(outcome.hasResult());
        assertNull(outcome.data(), "a missing source must not be synthesized as an empty one");
        assertEquals(0, profitServices.created.size(), "no calculation may run on absent inputs");
        assertTrue(outcome.freshness().provisional());
        assertFalse(outcome.freshness().resultAvailable());

        CalculationFreshness.SourceFreshness bank = sourceNamed(outcome, AccountSyncStateRepository.BANK);
        assertEquals(CalculationFreshness.SourceState.MISSING, bank.state());
        assertNull(bank.fetchedAt());
        CalculationFreshness.OutstandingRefresh work = only(outcome.freshness().outstandingWork());
        assertEquals(Set.of(AccountSyncStateRepository.BANK), work.sources(),
                "only the source that is actually not current is refreshed");
        assertEquals(AccountRefreshCoordinator.OPERATION, tasks.operations().get(0));
    }

    // ---------- what may and may not establish a final result ----------

    @Test
    void aSucceededRefreshTaskDoesNotMakeAResultFinalWhileTheMarkersAreStillStale() throws Exception {
        allSourcesAt(EXPIRED_AT);
        CachedCraftingProfitService service = service();

        CachedCraftingProfitService.ProfitOutcome first = service.profit(DiscChoice.all(), settings());
        String taskId = only(first.freshness().outstandingWork()).taskId();
        tasks.finish(taskId); // succeeded, but it refreshed nothing in this fake

        CachedCraftingProfitService.ProfitOutcome second = service.profit(DiscChoice.all(), settings());

        assertTrue(second.freshness().provisional(),
                "freshness comes from the captured input versions, never from a task's success");
        assertEquals(CalculationFreshness.SourceState.STALE,
                sourceNamed(second, AccountSyncStateRepository.BANK).state());
    }

    @Test
    void aForgottenTaskIsReportedAsBoundedRecoveryInformation() throws Exception {
        allSourcesAt(EXPIRED_AT);
        CachedCraftingProfitService service = service();

        CachedCraftingProfitService.ProfitOutcome first = service.profit(DiscChoice.all(), settings());
        String taskId = only(first.freshness().outstandingWork()).taskId();
        tasks.forget(taskId);

        CachedCraftingProfitService.ProfitOutcome second = service.profit(DiscChoice.all(), settings());

        CalculationFreshness.RefreshFailure failure = second.freshness().failures().stream()
                .filter(item -> "TASK_UNKNOWN".equals(item.code()))
                .findFirst().orElseThrow();
        assertEquals(CalculationFreshness.RefreshKind.ACCOUNT, failure.kind());
        assertTrue(failure.retryable());
        assertEquals("UNKNOWN", only(second.freshness().outstandingWork()).taskState());
    }

    // ---------- the envelope describes the captured inputs ----------

    @Test
    void theEnvelopeReportsTheVersionsCapturedBeforeTheCalculationNotNewerOnes() throws Exception {
        allSourcesAt(EXPIRED_AT);
        // A refresh completes while the calculation is running: the envelope must still describe
        // what this calculation actually used.
        profitServices.onReload = () -> allSourcesAt(NOW);

        CachedCraftingProfitService.ProfitOutcome outcome = service().profit(DiscChoice.all(), settings());

        assertTrue(outcome.hasResult());
        for (CalculationFreshness.SourceFreshness source : outcome.freshness().accountSources()) {
            assertEquals(EXPIRED_AT, source.fetchedAt(),
                    source.source() + " must be the captured version, not one read afterwards");
        }
        assertTrue(outcome.freshness().provisional());
    }

    @Test
    void theCalculationIdNamesTheInputGenerationAndChangesOnlyWithIt() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();

        String first = service.profit(DiscChoice.all(), settings()).freshness().calculationId();
        String again = service.profit(DiscChoice.all(), settings()).freshness().calculationId();
        assertEquals(first, again, "the same published versions must name the same generation");

        versions.put(AccountSyncStateRepository.CHARACTERS, FRESH_AT.plusSeconds(1));
        String afterAccountChange = service.profit(DiscChoice.all(), settings()).freshness().calculationId();
        assertNotEquals(first, afterAccountChange, "a changed account version is a new generation");

        coverage = new CalculationFreshness.PriceCoverage(
                99, 99, 0, 0, FRESH_AT, FRESH_AT, true, "quotes-a");
        String afterPriceChange = service.profit(DiscChoice.all(), settings()).freshness().calculationId();
        assertNotEquals(afterAccountChange, afterPriceChange,
                "a changed required item set is a new generation too");
    }

    @Test
    void anInteriorQuoteChangeIsANewGenerationEvenThoughEveryAggregateIsUnchanged() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();

        String before = service.profit(DiscChoice.all(), settings()).freshness().calculationId();

        // One required item's quote changed: refetched between the oldest and the newest instant, or
        // rewritten with new numbers under its existing one. Every aggregate the envelope reports is
        // identical, so only the quote version can tell this generation from the previous one.
        coverage = covered("quotes-b");
        CachedCraftingProfitService.ProfitOutcome after = service.profit(DiscChoice.all(), settings());

        assertEquals(covered().requiredItems(), after.freshness().tradingPost().requiredItems());
        assertEquals(covered().freshItems(), after.freshness().tradingPost().freshItems());
        assertEquals(covered().staleItems(), after.freshness().tradingPost().staleItems());
        assertEquals(covered().missingItems(), after.freshness().tradingPost().missingItems());
        assertEquals(covered().oldestFetchedAt(), after.freshness().tradingPost().oldestFetchedAt());
        assertEquals(covered().newestFetchedAt(), after.freshness().tradingPost().newestFetchedAt());
        assertNotEquals(before, after.freshness().calculationId(),
                "a calculation that read different quotes is a different calculation");
    }

    @Test
    void detailAfterAnInteriorQuoteChangeReportsChangedInputsAndCarriesItsOwnRow() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();
        String rowGeneration = service.profit(DiscChoice.all(), settings()).freshness().calculationId();

        // The caller now asks why the row it is showing says what it says. Meanwhile one interior
        // quote changed, leaving every coverage aggregate and both instants exactly as they were.
        coverage = covered("quotes-b");
        CraftResult recalculated = new CraftResult(
                100, "Artificer", 4, Map.of(), Map.of(), 1_300, 56, 7_890, 700, 2_800, null);
        profitServices.cannedRow = recalculated;

        CachedCraftingProfitService.ResolutionOutcome outcome =
                service.resolution(7, DiscChoice.all(), settings(), rowGeneration);

        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS,
                outcome.consistency(),
                "a changed quote must not be reported as explaining the caller's older row");
        assertNotEquals(rowGeneration, outcome.freshness().calculationId());
        assertEquals(recalculated, outcome.detail().row(),
                "and the response must carry the row recalculated from the quotes it actually read");
    }

    @Test
    void requestIdsAreDistinctPerRequestSoALateAnswerCannotBeMistakenForTheCurrentOne() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();

        String first = service.profit(DiscChoice.all(), settings()).freshness().requestId();
        String second = service.profit(DiscChoice.all(), settings()).freshness().requestId();

        assertNotEquals(first, second);
    }

    // ---------- resolution detail compatibility ----------

    @Test
    void detailExplainingTheCallersGenerationIsReportedAsAFreshCalculation() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();
        String generation = service.profit(DiscChoice.all(), settings()).freshness().calculationId();

        CachedCraftingProfitService.ResolutionOutcome outcome =
                service.resolution(7, DiscChoice.all(), settings(), generation);

        assertTrue(outcome.hasResult());
        assertEquals(CachedCraftingProfitService.CONSISTENCY_FRESH_CALCULATION, outcome.consistency());
        assertEquals(generation, outcome.freshness().calculationId());
    }

    @Test
    void detailComputedFromChangedInputsIsIdentifiedAndCarriesItsOwnMatchingRow() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();
        String oldGeneration = service.profit(DiscChoice.all(), settings()).freshness().calculationId();

        versions.put(AccountSyncStateRepository.MATERIALS, FRESH_AT.plusSeconds(30));

        CachedCraftingProfitService.ResolutionOutcome outcome =
                service.resolution(7, DiscChoice.all(), settings(), oldGeneration);

        assertTrue(outcome.hasResult());
        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS,
                outcome.consistency());
        assertNotEquals(oldGeneration, outcome.freshness().calculationId());
        assertEquals(RESULT, outcome.detail().row(),
                "the detail is paired with the row recalculated from its own generation");
    }

    @Test
    void detailWithoutAUsableSourceIsPendingRatherThanAFabricatedExplanation() throws Exception {
        allSourcesAt(FRESH_AT);
        versions.remove(AccountSyncStateRepository.CHARACTERS);

        CachedCraftingProfitService.ResolutionOutcome outcome =
                service().resolution(7, DiscChoice.all(), settings(), null);

        assertFalse(outcome.hasResult());
        assertNull(outcome.detail());
        assertFalse(outcome.freshness().resultAvailable());
        assertEquals(0, profitServices.created.size());
    }

    // ---------- the calculation reads one captured session ----------

    @Test
    void everyCalculationGetsItsOwnServiceOverTheSessionThatCapturedItsVersions() throws Exception {
        allSourcesAt(FRESH_AT);
        CachedCraftingProfitService service = service();

        service.profit(DiscChoice.all(), settings());
        service.profit(DiscChoice.all(), settings());

        assertEquals(2, profitServices.created.size(), "one calculation service per request");
        assertNotEquals(profitServices.created.get(0), profitServices.created.get(1));
        assertEquals(2, profitServices.sessions.size());
        assertNotEquals(profitServices.sessions.get(0), profitServices.sessions.get(1),
                "each request calculates over its own captured session");
    }

    // ---------- fixtures ----------

    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                ProfitInputSession::detached,
                profitServices,
                (con, sources) -> Map.copyOf(versions),
                con -> coverage,
                accountRefresh,
                priceRefresh,
                tasks,
                Clock.fixed(NOW, ZoneOffset.UTC),
                MAX_AGE);
    }

    private void allSourcesAt(Instant at) {
        versions.clear();
        for (String source : CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES) versions.put(source, at);
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }

    private static CalculationFreshness.PriceCoverage covered() {
        return covered("quotes-a");
    }

    /** The same aggregate coverage as {@link #covered()}, reading the named set of quotes. */
    private static CalculationFreshness.PriceCoverage covered(String quoteVersion) {
        return new CalculationFreshness.PriceCoverage(
                120, 120, 0, 0, FRESH_AT, FRESH_AT, true, quoteVersion);
    }

    private static CalculationFreshness.PriceCoverage uncovered() {
        return new CalculationFreshness.PriceCoverage(
                120, 10, 100, 10, EXPIRED_AT, FRESH_AT, false, "quotes-uncovered");
    }

    private static <T> T only(List<T> items) {
        assertEquals(1, items.size(), "expected exactly one item, got " + items);
        return items.get(0);
    }

    private static CalculationFreshness.SourceFreshness sourceNamed(
            CachedCraftingProfitService.ProfitOutcome outcome, String source) {
        return outcome.freshness().accountSources().stream()
                .filter(item -> item.source().equals(source))
                .findFirst().orElseThrow();
    }

    /** Hands out one recording calculation service per call, over the session it was given. */
    private final class StubProfitServices
            implements java.util.function.Function<ProfitInputSession, CraftingProfitService> {

        final List<CraftingProfitService> created = new ArrayList<>();
        final List<ProfitInputSession> sessions = new ArrayList<>();
        CraftingProfitService.ProfitData canned = new CraftingProfitService.ProfitData(
                List.of(RECIPE), List.of(RECIPE), Map.of(RECIPE.recipeId, RESULT), Map.of(), Map.of());
        /** The row a detail response recomputes from its own generation. */
        CraftResult cannedRow = RESULT;
        Runnable onReload;

        @Override
        public CraftingProfitService apply(ProfitInputSession session) {
            CraftingProfitService service = new CraftingProfitService(
                    null, null, null, null, null, null, null, session) {
                @Override
                public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
                    if (onReload != null) onReload.run();
                    return canned;
                }

                @Override
                public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                              CraftingSettings settings) {
                    if (onReload != null) onReload.run();
                    return new CraftingResolutionDetail(recipeId,
                            CraftingResolutionDetail.Status.AVAILABLE, RECIPE, cannedRow, null,
                            Map.of(), Map.of());
                }
            };
            created.add(service);
            sessions.add(session);
            return service;
        }
    }

    /**
     * A refresh that genuinely starts and then refuses to finish until the test releases it. This is
     * what makes "the cached result came back before the upstream work completed" an assertion
     * rather than an assumption: the result is checked while this gateway is still inside its call.
     */
    private static class HeldGateway {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean holding;

        void hold() {
            holding = true;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the refresh never started");
        }

        boolean stillInFlight() {
            return released.getCount() == 1;
        }

        void release() {
            released.countDown();
        }

        void block() throws InterruptedException {
            if (!holding) return;
            entered.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS), "the refresh was never released");
        }
    }

    private static final class HeldAccountGateway extends AccountRefreshGateway {

        private final HeldGateway held = new HeldGateway();

        void hold() { held.hold(); }
        void awaitEntered() throws InterruptedException { held.awaitEntered(); }
        boolean stillInFlight() { return held.stillInFlight(); }
        void release() { held.release(); }

        @Override public void syncAccountBank() throws Exception { held.block(); }
        @Override public void syncAccountMaterials() throws Exception { held.block(); }
        @Override public void syncAccountRecipes() throws Exception { held.block(); }
        @Override public void syncAccountLuck() throws Exception { held.block(); }
        @Override public void syncCharacterCraftingAndRecipes() throws Exception { held.block(); }
    }

    private static final class HeldPriceGateway extends TradingPostPriceRefreshGateway {

        private final HeldGateway held = new HeldGateway();

        void hold() { held.hold(); }
        void awaitEntered() throws InterruptedException { held.awaitEntered(); }
        boolean stillInFlight() { return held.stillInFlight(); }
        void release() { held.release(); }

        @Override public void syncTpPricesForProfit() throws Exception { held.block(); }
    }
}
