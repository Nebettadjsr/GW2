package sync;

/**
 * Thin instantiable seam over the static {@link TpSync} price-refresh calls (STORY-APP-006),
 * mirroring {@link AccountRefreshGateway}/{@link GlobalDataRefreshGateway}'s precedent:
 * {@code TpSync} is a non-instantiable utility class with no overridable methods, so this
 * gateway exists purely to give {@code application.TradingPostPriceRefreshService} a
 * replaceable collaborator: a fake subclass substitutes for it in application-layer tests
 * (TARGET_ARCHITECTURE.md §25) without any live GW2 API or database access.
 */
public class TradingPostPriceRefreshGateway {

    public void syncTpPricesForDiscovery() throws Exception {
        TpSync.syncTpPricesForDiscovery();
    }

    public void syncTpPricesForProfit() throws Exception {
        TpSync.syncTpPricesForProfit();
    }
}
