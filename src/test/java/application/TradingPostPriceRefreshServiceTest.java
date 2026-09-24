package application;

import org.junit.jupiter.api.Test;
import sync.TradingPostPriceRefreshGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-006) for
 * {@link TradingPostPriceRefreshService}: a fake {@link TradingPostPriceRefreshGateway} replaces
 * the live GW2 API/database calls, so these run without HTTP or PostgreSQL. Proves each
 * distinctly-scoped variant delegates to its own gateway call and that a failure propagates
 * unchanged - matching {@code sync.TpSync.syncTpPricesForDiscovery}/{@code ForProfit}'s existing
 * pre-extraction behavior at their respective call sites.
 */
class TradingPostPriceRefreshServiceTest {

    @Test
    void refreshForDiscoveryDelegatesToTheDiscoveryGatewayCall() throws Exception {
        var gateway = new RecordingGateway();
        var service = new TradingPostPriceRefreshService(gateway);

        service.refreshForDiscovery();

        assertEquals(List.of("discovery"), gateway.calls);
    }

    @Test
    void refreshForProfitDelegatesToTheProfitGatewayCall() throws Exception {
        var gateway = new RecordingGateway();
        var service = new TradingPostPriceRefreshService(gateway);

        service.refreshForProfit();

        assertEquals(List.of("profit"), gateway.calls);
    }

    @Test
    void discoveryFailurePropagatesUnchanged() {
        var gateway = new RecordingGateway();
        gateway.discoveryFailure = new IOException("simulated discovery price fetch failure");
        var service = new TradingPostPriceRefreshService(gateway);

        Exception thrown = assertThrows(IOException.class, service::refreshForDiscovery);

        assertSame(gateway.discoveryFailure, thrown);
    }

    @Test
    void profitFailurePropagatesUnchanged() {
        var gateway = new RecordingGateway();
        gateway.profitFailure = new RuntimeException("simulated profit price fetch failure");
        var service = new TradingPostPriceRefreshService(gateway);

        Exception thrown = assertThrows(RuntimeException.class, service::refreshForProfit);

        assertSame(gateway.profitFailure, thrown);
    }

    private static class RecordingGateway extends TradingPostPriceRefreshGateway {
        final List<String> calls = new ArrayList<>();
        Exception discoveryFailure;
        Exception profitFailure;

        @Override
        public void syncTpPricesForDiscovery() throws Exception {
            if (discoveryFailure != null) throw discoveryFailure;
            calls.add("discovery");
        }

        @Override
        public void syncTpPricesForProfit() throws Exception {
            if (profitFailure != null) throw profitFailure;
            calls.add("profit");
        }
    }
}
