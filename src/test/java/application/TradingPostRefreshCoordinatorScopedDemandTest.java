package application;

import model.AccountScope;
import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** STORY-API-011: shared quote demand carries the requesting account's required ids, never the no-scope fallback. */
class TradingPostRefreshCoordinatorScopedDemandTest {

    private final List<String> calls = new ArrayList<>();
    private final TradingPostPriceRefreshService service = new TradingPostPriceRefreshService(
            new TradingPostPriceRefreshGateway() {
                @Override public void syncTpPricesForProfit() { calls.add("global"); }
                @Override public void syncTpPricesForProfit(AccountScope scope) { calls.add(scope.id()); }
                @Override public void syncTpPricesForDiscovery(AccountScope scope) { calls.add("d:" + scope.id()); }
            });

    @Test
    void aScopedDemandRefreshesThatAccountsRequiredIdsOnly() throws Exception {
        FakeBackgroundTasks tasks = new FakeBackgroundTasks();
        TradingPostRefreshCoordinator coordinator = new TradingPostRefreshCoordinator(service, tasks);
        AccountScope a = AccountScope.of(UUID.randomUUID());
        AccountScope b = AccountScope.of(UUID.randomUUID());

        var first = coordinator.requireProfitCoverage(a);
        var second = coordinator.requireProfitCoverage(b);
        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, first.reuse());
        assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN, second.reuse());
        assertEquals(1, tasks.admitted.size(), "one shared task, not a pipeline per account");

        tasks.run(first.taskId());
        assertEquals(List.of(a.id(), b.id()), calls);
    }

    @Test
    void anUnscopedDemandStillUsesTheGlobalSelectionAndSynchronousDiscoveryIsScoped() throws Exception {
        TradingPostRefreshCoordinator coordinator = new TradingPostRefreshCoordinator(service, new FakeBackgroundTasks());
        AccountScope a = AccountScope.of(UUID.randomUUID());
        coordinator.refreshAndAwait(TradingPostRefreshCoordinator.Variant.PROFIT);
        coordinator.refreshAndAwait(TradingPostRefreshCoordinator.Variant.DISCOVERY, a);
        assertEquals(List.of("global", "d:" + a.id()), calls);
    }

    @Test
    void explicitGlobalDemandIsRetainedWhenScopedDemandJoinsTheSameDrain() throws Exception {
        FakeBackgroundTasks tasks = new FakeBackgroundTasks();
        TradingPostRefreshCoordinator coordinator = new TradingPostRefreshCoordinator(service, tasks);
        AccountScope a = AccountScope.of(UUID.randomUUID());

        var first = coordinator.require(TradingPostRefreshCoordinator.Variant.PROFIT);
        var second = coordinator.requireProfitCoverage(a);
        assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN, second.reuse());

        tasks.run(first.taskId());
        assertEquals(List.of("global", a.id()), calls, "manual/global demand and scoped demand both execute");
    }
}
