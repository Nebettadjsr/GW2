package application;

import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import sync.AccountRefreshGateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criterion 5 and docs/bugs/BUG-2026-001: account refresh keeps one writer,
 * never reports a source it did not refresh as refreshed, and picks up demands that arrive while a
 * narrower batch is in flight.
 *
 * <p>Every interleaving here is driven explicitly — the task facility is a fake that runs a body
 * only when a test says so, and the gateway blocks on a latch the test opens — so these assertions
 * do not depend on timing, a sleep, a live GW2 API or a database.
 */
class AccountRefreshCoordinatorTest {

    private static final Set<String> ALL_FOUR = Set.of(
            AccountSyncStateRepository.BANK,
            AccountSyncStateRepository.MATERIALS,
            AccountSyncStateRepository.RECIPES,
            AccountSyncStateRepository.CHARACTERS);

    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final RecordingGateway gateway = new RecordingGateway();
    private final AccountRefreshCoordinator coordinator =
            new AccountRefreshCoordinator(new AccountRefreshService(gateway), tasks);

    // ---------- the confirmed overlapping-scope defect ----------

    @Test
    void anUnrelatedNarrowAccountTaskIsNotReportedAsRefreshingTheCallersSources() {
        // The selector-options route's CHARACTERS-only sync, still unfinished.
        String foreign = tasks.admitForeign(AccountRefreshCoordinator.OPERATION);

        AccountRefreshCoordinator.Admission admission = coordinator.require(ALL_FOUR);

        assertEquals(AccountRefreshCoordinator.REUSE_WAITING_FOR_UNRELATED_TASK, admission.reuse(),
                "the caller must be told this task is not refreshing its sources");
        assertEquals(foreign, admission.taskId());
        assertEquals(ALL_FOUR, admission.sources());
        assertEquals(ALL_FOUR, coordinator.outstandingSources(),
                "the demand must survive, because nothing refreshed it");
        assertEquals(List.of(), gateway.calls, "no second account writer may start");
        assertEquals(Set.of(), coordinator.lastRefreshedSources(),
                "nothing was refreshed, so nothing may be claimed as refreshed");
        assertEquals(1, tasks.admittedFor(AccountRefreshCoordinator.OPERATION),
                "at most one unfinished ACCOUNT_SYNC");
    }

    @Test
    void onceTheNarrowTaskEndsTheRemainingSourcesAreRecheckedAndAdmitted() {
        String foreign = tasks.admitForeign(AccountRefreshCoordinator.OPERATION);
        coordinator.require(ALL_FOUR);

        tasks.finish(foreign);

        AccountRefreshCoordinator.Admission second = coordinator.require(ALL_FOUR);
        assertEquals(AccountRefreshCoordinator.REUSE_ADMITTED, second.reuse());
        assertFalse(foreign.equals(second.taskId()), "a new run, not the ended one");

        tasks.run(second.taskId());

        assertEquals(List.of("BANK", "MATERIALS", "ACCOUNT_RECIPES", "CHARACTERS"), gateway.calls,
                "all four required sources are refreshed, in the use case's established order");
        assertEquals(ALL_FOUR, coordinator.lastRefreshedSources());
        assertEquals(Set.of(), coordinator.outstandingSources());
        assertEquals(1, gateway.maxConcurrent.get(), "account writes must never overlap");
    }

    // ---------- reuse, queueing and write exclusion ----------

    @Test
    void anActiveRunOfSufficientScopeIsReusedWithoutAdmittingMoreWork() throws Exception {
        AccountRefreshCoordinator.Admission first =
                coordinator.require(Set.of(AccountSyncStateRepository.CHARACTERS));
        assertEquals(AccountRefreshCoordinator.REUSE_ADMITTED, first.reuse());

        gateway.blockOn("CHARACTERS");
        Thread run = runInBackground(first.taskId());
        gateway.awaitEntered("CHARACTERS");

        AccountRefreshCoordinator.Admission second =
                coordinator.require(Set.of(AccountSyncStateRepository.CHARACTERS));
        assertEquals(AccountRefreshCoordinator.REUSE_SUFFICIENT_SCOPE, second.reuse());
        assertEquals(first.taskId(), second.taskId());
        assertEquals(1, tasks.admittedFor(AccountRefreshCoordinator.OPERATION));

        gateway.release("CHARACTERS");
        run.join();

        assertEquals(List.of("CHARACTERS"), gateway.calls,
                "a sufficient active scope must not cause the same source to be fetched twice");
    }

    @Test
    void sourcesDemandedWhileANarrowerBatchRunsAreRefreshedByTheSameSingleWriter() throws Exception {
        AccountRefreshCoordinator.Admission narrow =
                coordinator.require(Set.of(AccountSyncStateRepository.CHARACTERS));

        gateway.blockOn("CHARACTERS");
        Thread run = runInBackground(narrow.taskId());
        gateway.awaitEntered("CHARACTERS");

        AccountRefreshCoordinator.Admission broader = coordinator.require(ALL_FOUR);
        assertEquals(AccountRefreshCoordinator.REUSE_QUEUED_BEHIND_ACTIVE_RUN, broader.reuse(),
                "the active batch is narrower, so the rest is queued rather than claimed");
        assertEquals(narrow.taskId(), broader.taskId());
        assertEquals(1, tasks.admittedFor(AccountRefreshCoordinator.OPERATION),
                "no concurrent account writer is started for the remaining sources");

        gateway.release("CHARACTERS");
        run.join();

        assertEquals(List.of("CHARACTERS", "BANK", "MATERIALS", "ACCOUNT_RECIPES"), gateway.calls,
                "the same run refreshes the sources demanded while it was busy");
        assertEquals(1, gateway.maxConcurrent.get(), "account writes must never overlap");
        assertEquals(Set.of(), coordinator.outstandingSources());
        assertEquals("SUCCEEDED", tasks.stateOf(narrow.taskId()));
    }

    @Test
    void concurrentDemandsNeverAdmitASecondAccountWriter() throws Exception {
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<AccountRefreshCoordinator.Admission> admissions =
                Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < callers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                admissions.add(coordinator.require(ALL_FOUR));
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) thread.join();

        assertEquals(callers, admissions.size());
        assertEquals(1, tasks.admittedFor(AccountRefreshCoordinator.OPERATION),
                "eight concurrent demands must coalesce into one account run");
        String only = tasks.lastIdFor(AccountRefreshCoordinator.OPERATION);
        for (AccountRefreshCoordinator.Admission admission : admissions) {
            assertEquals(only, admission.taskId());
        }

        tasks.run(only);
        assertEquals(ALL_FOUR, coordinator.lastRefreshedSources());
        assertEquals(1, gateway.maxConcurrent.get());
    }

    // ---------- failure ----------

    @Test
    void aFailedRunReportsRecoveryInformationAndLeavesTheWriterFreeForTheNextRequest() {
        gateway.failOn("BANK");

        AccountRefreshCoordinator.Admission first =
                coordinator.require(Set.of(AccountSyncStateRepository.BANK));
        tasks.runExpectingFailure(first.taskId());

        CalculationFreshness.RefreshFailure failure = coordinator.lastFailure().orElseThrow();
        assertEquals(CalculationFreshness.RefreshKind.ACCOUNT, failure.kind());
        assertEquals("ACCOUNT_REFRESH_FAILED", failure.code());
        assertTrue(failure.retryable());
        assertTrue(failure.message().contains("unchanged"),
                "the message must say the previously synchronized data is still there: " + failure.message());
        assertEquals("FAILED", tasks.stateOf(first.taskId()));
        assertEquals(Set.of(), coordinator.outstandingSources(),
                "a failed batch is not retried in a loop inside the same run");

        gateway.succeedAgain();
        AccountRefreshCoordinator.Admission second =
                coordinator.require(Set.of(AccountSyncStateRepository.BANK));
        assertEquals(AccountRefreshCoordinator.REUSE_ADMITTED, second.reuse());
        tasks.run(second.taskId());
        assertEquals(Set.of(AccountSyncStateRepository.BANK), coordinator.lastRefreshedSources());
        assertTrue(coordinator.lastFailure().isEmpty(), "a later success clears the recovery item");
    }

    @Test
    void anEmptyDemandAdmitsNothing() {
        AccountRefreshCoordinator.Admission admission = coordinator.require(Set.of());

        assertEquals(AccountRefreshCoordinator.REUSE_ADMITTED, admission.reuse());
        assertSame(null, admission.taskId());
        assertEquals(0, tasks.admittedFor(AccountRefreshCoordinator.OPERATION),
                "a caller with nothing stale must not trigger an upstream fetch");
    }

    private Thread runInBackground(String taskId) {
        Thread thread = new Thread(() -> tasks.run(taskId));
        thread.start();
        return thread;
    }

    /**
     * Records which sources were fetched, in order, and refuses to be entered twice at once so an
     * overlapping account write fails the test rather than merely being unlikely.
     */
    private static final class RecordingGateway extends AccountRefreshGateway {

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger maxConcurrent = new AtomicInteger();

        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile String blockOn;
        private volatile String failOn;
        private CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch released = new CountDownLatch(1);

        void blockOn(String source) {
            blockOn = source;
            entered = new CountDownLatch(1);
            released = new CountDownLatch(1);
        }

        void awaitEntered(String source) throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the run never reached " + source);
        }

        void release(String source) {
            released.countDown();
        }

        void failOn(String source) {
            failOn = source;
        }

        void succeedAgain() {
            failOn = null;
        }

        @Override public void syncAccountBank() throws Exception { record("BANK"); }
        @Override public void syncAccountMaterials() throws Exception { record("MATERIALS"); }
        @Override public void syncAccountRecipes() throws Exception { record("ACCOUNT_RECIPES"); }
        @Override public void syncAccountLuck() throws Exception { record("LUCK"); }
        @Override public void syncCharacterCraftingAndRecipes() throws Exception { record("CHARACTERS"); }

        private void record(String source) throws Exception {
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                calls.add(source);
                if (source.equals(failOn)) throw new IllegalStateException("upstream refused " + source);
                if (source.equals(blockOn)) {
                    entered.countDown();
                    assertTrue(released.await(5, TimeUnit.SECONDS), "never released at " + source);
                }
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }
}
