package web;

import application.CraftingDiscoveryService;
import application.TradingPostPriceRefreshService;
import application.TradingPostRefreshCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;
import web.dto.CraftingDiscoveryRequest;
import web.dto.CraftingDiscoveryResolutionRequest;
import web.task.BackgroundTaskService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STORY-SYNC-006: both real Discovery calculation routes obtain their quotes through the shared
 * {@link TradingPostRefreshCoordinator} while keeping the synchronous contract - the calculation is
 * only started once the refresh has completed, and a refresh failure reaches the caller unchanged.
 * Uses the real task facility, coordinator, manual-refresh controller and Discovery controller; only
 * the quote store is an in-memory stand-in (ten-minute freshness, required-id-only fetches).
 */
class DiscoveryCalculationQuoteCoordinationTest {

    private static final CraftingDiscoveryRequest REQUEST = new CraftingDiscoveryRequest(
            new CraftingDiscoveryRequest.ScopeDto("Chef", "Aria", 400), null);

    private final BackgroundTaskService service = new BackgroundTaskService();
    private final Store store = new Store();
    private final TradingPostRefreshCoordinator coordinator = new TradingPostRefreshCoordinator(
            new TradingPostPriceRefreshService(store), new BackgroundTaskServiceTasks(service));
    private final PriceRefreshApiController manual = new PriceRefreshApiController(coordinator);
    private final ExecutorService callers = Executors.newFixedThreadPool(2);
    private final List<Set<Integer>> coveredWhenCalculationStarted = Collections.synchronizedList(new ArrayList<>());

    private final Supplier<CraftingDiscoveryService> factory = () -> {
        coveredWhenCalculationStarted.add(Set.copyOf(store.quotes.keySet()));
        throw new IllegalStateException("calculation reached");
    };

    private static org.springframework.mock.web.MockHttpServletRequest http() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(web.access.RequestAccountContext.ATTRIBUTE, new application.access.AccountContext(
                AuthorizedRequests.ACCOUNT_ID.toString(), Set.of(), Set.of(), "ctx-1",
                java.time.Instant.parse("2099-01-01T00:00:00Z")));
        return request;
    }

    @AfterEach
    void close() {
        store.release.countDown();
        callers.shutdownNow();
        service.close();
    }

    @Test
    void tableRouteWaitsForOverlappingManualRunThenCoversIdsAddedMeanwhile() throws Exception {
        assertCalculationWaits(new CraftingDiscoveryApiController(factory, coordinator),
                c -> c.discovery(http(), REQUEST));
    }

    @Test
    void resolutionRouteWaitsForOverlappingManualRunThenCoversIdsAddedMeanwhile() throws Exception {
        var body = new CraftingDiscoveryResolutionRequest(7, REQUEST);
        assertCalculationWaits(new CraftingDiscoveryApiController(factory, coordinator),
                c -> c.discoveryResolution(http(), body));
    }

    private interface Call {
        void run(CraftingDiscoveryApiController controller) throws Exception;
    }

    private void assertCalculationWaits(CraftingDiscoveryApiController controller, Call call) throws Exception {
        store.discoveryIds.addAll(Set.of(1, 2));
        store.quotes.put(2, store.now);
        store.holdFirstFetch = true;

        assertEquals(202, manual.refreshPrices(Map.of("variant", "DISCOVERY")).getStatusCode().value());
        assertTrue(store.firstFetchEntered.await(5, TimeUnit.SECONDS));
        store.discoveryIds.add(3);

        Future<?> calculation = callers.submit(() -> {
            call.run(controller);
            return null;
        });
        Thread.sleep(150);
        assertFalse(calculation.isDone(), "calculation must not run against missing quotes");
        assertTrue(coveredWhenCalculationStarted.isEmpty());

        store.release.countDown();
        ExecutionException reached = assertThrows(ExecutionException.class, () -> calculation.get(10, TimeUnit.SECONDS));
        assertEquals("calculation reached", reached.getCause().getMessage());
        assertEquals(List.of(Set.of(1, 2, 3)), coveredWhenCalculationStarted);

        List<Integer> fetched = new ArrayList<>();
        store.batches.forEach(fetched::addAll);
        Collections.sort(fetched);
        assertEquals(List.of(1, 3), fetched, "fresh id 2 reused; each missing id fetched exactly once");
        assertEquals(1, store.maxConcurrentFetches.get());
    }

    @Test
    void refreshFailureReachesTheCallerUnchangedAndNoCalculationStarts() {
        store.failure = new IllegalArgumentException("upstream down");
        store.discoveryIds.add(1);
        var controller = new CraftingDiscoveryApiController(factory, coordinator);

        Exception thrown = assertThrows(Exception.class, () -> controller.discovery(http(), REQUEST));
        assertSame(store.failure, thrown);
        assertTrue(coveredWhenCalculationStarted.isEmpty());
        assertTrue(coordinator.lastFailure(TradingPostRefreshCoordinator.Variant.DISCOVERY).isPresent());
    }

    private static final class Store extends TradingPostPriceRefreshGateway {
        final long now = 1_000_000L;
        final Set<Integer> discoveryIds = ConcurrentHashMap.newKeySet();
        final Map<Integer, Long> quotes = new ConcurrentHashMap<>();
        final List<List<Integer>> batches = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch firstFetchEntered = new CountDownLatch(1);
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrentFetches = new AtomicInteger();
        volatile boolean holdFirstFetch;
        volatile Exception failure;
        private boolean held;

        @Override
        public void syncTpPricesForDiscovery() throws Exception {
            if (failure != null) throw failure;
            List<Integer> missing = new ArrayList<>(new TreeSet<>(discoveryIds));
            missing.removeIf(quotes::containsKey);
            if (missing.isEmpty()) return;
            maxConcurrentFetches.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                boolean hold;
                synchronized (this) {
                    hold = holdFirstFetch && !held;
                    held = true;
                }
                if (hold) {
                    firstFetchEntered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) fail("not released");
                }
                batches.add(missing);
                missing.forEach(id -> quotes.put(id, now));
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }
}
