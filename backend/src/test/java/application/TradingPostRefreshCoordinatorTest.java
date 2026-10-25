package application;

import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criterion 6: Trading Post refresh is coordinated independently of account
 * refresh, concurrent coverage demands coalesce into one run, a demand raised during a run earns
 * exactly one recheck pass afterwards, and a failure exposes bounded recovery information.
 *
 * <p>Driven by an explicit task fake and a latched gateway, so no assertion depends on timing. The
 * item set, the ten-minute reuse policy and the batched fetch are deliberately not re-implemented
 * here: this suite asserts only which runs happen, because selecting items stays behind the shared
 * {@link TradingPostPriceRefreshService} path Profit and Discovery both use.
 */
class TradingPostRefreshCoordinatorTest {

    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final LatchedGateway gateway = new LatchedGateway();
    private final TradingPostRefreshCoordinator coordinator =
            new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(gateway), tasks);

    @Test
    void oneDemandRunsOnePassAndLeavesNoFurtherWork() {
        TradingPostRefreshCoordinator.Admission admission = coordinator.requireProfitCoverage();

        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, admission.reuse());
        tasks.run(admission.taskId());

        assertEquals(1, gateway.profitRefreshes.get());
        assertEquals(1, coordinator.completedPasses());
        assertFalse(coordinator.refreshing());
        assertEquals(1, tasks.admittedFor(TradingPostRefreshCoordinator.PROFIT_OPERATION));
    }

    @Test
    void concurrentDemandsCoalesceIntoOneRunAndEarnExactlyOneRecheckPass() throws Exception {
        TradingPostRefreshCoordinator.Admission first = coordinator.requireProfitCoverage();
        gateway.blockNextRefresh();

        Thread run = new Thread(() -> tasks.run(first.taskId()));
        run.start();
        gateway.awaitEntered();

        List<TradingPostRefreshCoordinator.Admission> overlapping =
                Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread caller = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                overlapping.add(coordinator.requireProfitCoverage());
            });
            caller.start();
            callers.add(caller);
        }
        start.countDown();
        for (Thread caller : callers) caller.join();

        for (TradingPostRefreshCoordinator.Admission admission : overlapping) {
            assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN, admission.reuse());
            assertEquals(first.taskId(), admission.taskId());
        }
        assertEquals(1, tasks.admittedFor(TradingPostRefreshCoordinator.PROFIT_OPERATION),
                "overlapping demands must not start competing fetches of overlapping item sets");
        assertEquals(1, gateway.maxConcurrent.get(), "only one fetch may be in flight");

        gateway.release();
        run.join();

        assertEquals(2, gateway.profitRefreshes.get(),
                "the initial pass plus exactly one recheck for everything demanded while it ran");
        assertEquals(2, coordinator.completedPasses());
        assertFalse(coordinator.refreshing());
    }

    @Test
    void anUnrelatedPriceRefreshTaskIsWaitedOutRatherThanAdopted() {
        String foreign = tasks.admitForeign(TradingPostRefreshCoordinator.PROFIT_OPERATION);

        TradingPostRefreshCoordinator.Admission admission = coordinator.requireProfitCoverage();

        assertEquals(TradingPostRefreshCoordinator.REUSE_WAITING_FOR_UNRELATED_TASK, admission.reuse());
        assertEquals(foreign, admission.taskId());
        assertEquals(0, gateway.profitRefreshes.get(), "no second fetch runs beside the existing one");

        tasks.finish(foreign);
        TradingPostRefreshCoordinator.Admission second = coordinator.requireProfitCoverage();
        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, second.reuse());
        tasks.run(second.taskId());
        assertEquals(1, gateway.profitRefreshes.get());
    }

    @Test
    void aFailedFetchReportsBoundedRecoveryInformationAndCanBeDemandedAgain() {
        gateway.failNextRefresh();

        TradingPostRefreshCoordinator.Admission first = coordinator.requireProfitCoverage();
        tasks.runExpectingFailure(first.taskId());

        CalculationFreshness.RefreshFailure failure = coordinator.lastFailure().orElseThrow();
        assertEquals(CalculationFreshness.RefreshKind.TRADING_POST, failure.kind());
        assertEquals("PRICE_REFRESH_FAILED", failure.code());
        assertTrue(failure.retryable());
        assertTrue(failure.message().contains("unchanged"),
                "a failed fetch leaves the previously fetched quotes in place: " + failure.message());
        assertEquals("FAILED", tasks.stateOf(first.taskId()));
        assertFalse(coordinator.refreshing());

        TradingPostRefreshCoordinator.Admission second = coordinator.requireProfitCoverage();
        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, second.reuse());
        tasks.run(second.taskId());
        assertEquals(1, coordinator.completedPasses());
        assertTrue(coordinator.lastFailure().isEmpty());
    }

    @Test
    void theDiscoveryVariantIsNotTouchedByProfitCoordination() {
        TradingPostRefreshCoordinator.Admission admission = coordinator.requireProfitCoverage();
        tasks.run(admission.taskId());

        assertEquals(1, gateway.profitRefreshes.get());
        assertEquals(0, gateway.discoveryRefreshes.get(),
                "Profit coordination must not refresh Discovery's item set");
        assertEquals(List.of(TradingPostRefreshCoordinator.PROFIT_OPERATION), tasks.operations(),
                "account and Discovery operation keys stay separate");
    }

    /** Counts the shared refresh calls and can hold one open or fail it, on the test's command. */
    private static final class LatchedGateway extends TradingPostPriceRefreshGateway {

        final AtomicInteger profitRefreshes = new AtomicInteger();
        final AtomicInteger discoveryRefreshes = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile boolean blockNext;
        private volatile boolean failNext;
        private CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch released = new CountDownLatch(1);

        void blockNextRefresh() {
            blockNext = true;
            entered = new CountDownLatch(1);
            released = new CountDownLatch(1);
        }

        void failNextRefresh() {
            failNext = true;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the refresh never started");
        }

        void release() {
            released.countDown();
        }

        @Override
        public void syncTpPricesForProfit() throws Exception {
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                profitRefreshes.incrementAndGet();
                if (failNext) {
                    failNext = false;
                    throw new IllegalStateException("upstream refused the quote batch");
                }
                if (blockNext) {
                    blockNext = false;
                    entered.countDown();
                    assertTrue(released.await(5, TimeUnit.SECONDS), "the refresh was never released");
                }
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void syncTpPricesForDiscovery() {
            discoveryRefreshes.incrementAndGet();
        }
    }
}
