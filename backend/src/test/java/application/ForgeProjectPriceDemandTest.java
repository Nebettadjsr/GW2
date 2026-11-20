package application;

import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A selected Forge project demands exactly its own explicit positive ids on the shared single-flight run. */
class ForgeProjectPriceDemandTest {
    private final List<Set<Integer>> idCalls = new ArrayList<>();
    private final List<String> otherCalls = new ArrayList<>();
    private final TradingPostPriceRefreshService service = new TradingPostPriceRefreshService(
            new TradingPostPriceRefreshGateway() {
                @Override public void syncTpPricesForIds(Set<Integer> ids) { idCalls.add(new TreeSet<>(ids)); }
                @Override public void syncTpPricesForProfit() { otherCalls.add("global"); }
                @Override public void syncTpPricesForProfit(model.AccountScope s) { otherCalls.add("scope"); }
            });

    @Test
    void sameIdsFromTwoProjectsCoalesceIntoOnePassAndFilterNonPositiveIds() throws Exception {
        FakeBackgroundTasks tasks = new FakeBackgroundTasks();
        var coordinator = new TradingPostRefreshCoordinator(service, tasks);
        var first = coordinator.requireItems(List.of(19745, 19748, -3, 0), null);
        var second = coordinator.requireItems(List.of(19748, 20796), null);
        assertEquals(TradingPostRefreshCoordinator.REUSE_ADMITTED, first.reuse());
        assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN, second.reuse());
        assertEquals(1, tasks.admitted.size());
        tasks.run(first.taskId());
        assertEquals(List.of(Set.of(19745, 19748, 20796)), idCalls, "each id once, positive only");
        assertEquals(List.of(), otherCalls, "no ordinary/global/whole-catalogue work was demanded");
    }

    @Test
    void emptyOrNonPositiveDemandAdmitsNothing() {
        FakeBackgroundTasks tasks = new FakeBackgroundTasks();
        var coordinator = new TradingPostRefreshCoordinator(service, tasks);
        var admission = coordinator.requireItems(List.of(0, -1), null);
        assertNull(admission.taskId());
        assertEquals(0, tasks.admitted.size());
    }
}
