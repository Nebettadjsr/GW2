package application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;
import web.BackgroundTaskServiceTasks;
import web.PriceRefreshApiController;
import web.task.BackgroundTaskService;
import web.task.TaskAlreadyRunningException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STORY-SYNC-006 criterion 6: every entry path for shared Trading Post quotes - the cached
 * calculation's demand and the manual route, for both Profit and Discovery - is admitted by the one
 * {@link TradingPostRefreshCoordinator}. The real task facility and the real
 * {@link TradingPostPriceRefreshService} are used; only the upstream/quote store is an in-memory
 * stand-in for {@code tp_prices} with the ten-minute "fresh" rule and required-id-only fetching.
 */
class TradingPostSharedDemandCoordinationTest {

    private final BackgroundTaskService service = new BackgroundTaskService();
    private final QuoteStore store = new QuoteStore();
    private final TradingPostRefreshCoordinator coordinator = new TradingPostRefreshCoordinator(
            new TradingPostPriceRefreshService(store), new BackgroundTaskServiceTasks(service));
    private final PriceRefreshApiController manual = new PriceRefreshApiController(coordinator);

    @AfterEach
    void close() {
        store.release.countDown();
        service.close();
    }

    @Test
    void multiFeatureDemandsReuseFreshQuotesNeverFetchOverlapsTwiceAndCoverAdditionalIds() throws Exception {
        // acct-A's calculation needs {1,2,3}; acct-B's discovery needs {2,3,4}; 3 is already fresh.
        store.profitIds.addAll(Set.of(1, 2, 3));
        store.discoveryIds.addAll(Set.of(2, 3, 4));
        store.quotes.put(3, store.now);
        store.holdFirstFetch = true;

        var profit = coordinator.requireProfitCoverage();
        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, profit.reuse());
        assertTrue(store.firstFetchEntered.await(5, TimeUnit.SECONDS));

        // Manual Discovery request while Profit is mid-fetch: its own task, no concurrent fetch.
        var accepted = manual.refreshPrices(Map.of("variant", "DISCOVERY"));
        assertEquals(202, accepted.getStatusCode().value());
        String discoveryTask = accepted.getBody().taskId();
        awaitRunning(discoveryTask);

        // A further account's demand adds id 5 for Profit and a manual duplicate is refused (409)
        // but still earns the running Profit task its one recheck pass.
        store.profitIds.add(5);
        var joined = coordinator.requireProfitCoverage();
        assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN, joined.reuse());
        assertEquals(profit.taskId(), joined.taskId());
        var refused = assertThrows(TaskAlreadyRunningException.class,
                () -> manual.refreshPrices(Map.of("variant", "PROFIT")));
        assertEquals(profit.taskId(), refused.existingTaskId());

        store.release.countDown();
        awaitTerminal(profit.taskId());
        awaitTerminal(discoveryTask);

        List<Integer> fetched = new ArrayList<>();
        store.batches.forEach(fetched::addAll);
        Collections.sort(fetched);
        assertEquals(List.of(1, 2, 4, 5), fetched, "fresh id 3 reused; overlapping id 2 fetched exactly once");
        assertEquals(Set.of(1, 2, 3, 4, 5), store.quotes.keySet());
        assertEquals(1, store.maxConcurrentFetches.get(), "Profit and Discovery never fetch at the same time");

        // Public task data carries execution facts only.
        String publicData = service.find(profit.taskId()).orElseThrow() + " " + service.find(discoveryTask).orElseThrow()
                + " " + accepted.getBody();
        assertFalse(publicData.contains("acct-"));
        assertEquals("PRICE_REFRESH_DISCOVERY", service.find(discoveryTask).orElseThrow().operation());
        assertEquals("PRICE_REFRESH_PROFIT", service.find(profit.taskId()).orElseThrow().operation());
    }

    private void awaitRunning(String taskId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (service.find(taskId).orElseThrow().state().name().equals("RUNNING")) return;
            Thread.sleep(10);
        }
        fail("task never started");
    }

    private void awaitTerminal(String taskId) throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            if (service.find(taskId).orElseThrow().state().isTerminal()) return;
            Thread.sleep(10);
        }
        fail("task did not finish: " + taskId);
    }

    /** In-memory stand-in for the shared quote table and the required-id-only batched fetch. */
    private static final class QuoteStore extends TradingPostPriceRefreshGateway {
        final long now = 1_000_000L;
        final Set<Integer> profitIds = ConcurrentHashMap.newKeySet();
        final Set<Integer> discoveryIds = ConcurrentHashMap.newKeySet();
        /** item id -> fetch time; present means fresh (inside the ten-minute window). */
        final Map<Integer, Long> quotes = new ConcurrentHashMap<>();
        final List<List<Integer>> batches = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch firstFetchEntered = new CountDownLatch(1);
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrentFetches = new AtomicInteger();
        volatile boolean holdFirstFetch;
        private boolean held;

        @Override
        public void syncTpPricesForProfit() throws Exception {
            fetchMissing(profitIds);
        }

        @Override
        public void syncTpPricesForDiscovery() throws Exception {
            fetchMissing(discoveryIds);
        }

        private void fetchMissing(Set<Integer> required) throws Exception {
            List<Integer> missing = new ArrayList<>(new TreeSet<>(required));
            missing.removeIf(quotes::containsKey);
            if (missing.isEmpty()) return;
            int now = concurrent.incrementAndGet();
            maxConcurrentFetches.accumulateAndGet(now, Math::max);
            try {
                boolean hold;
                synchronized (this) {
                    hold = holdFirstFetch && !held;
                    held = true;
                }
                if (hold) {
                    firstFetchEntered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                }
                batches.add(missing);
                missing.forEach(id -> quotes.put(id, this.now));
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }
}
