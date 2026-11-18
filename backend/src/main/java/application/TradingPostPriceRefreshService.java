package application;

import sync.TradingPostPriceRefreshGateway;

/**
 * Application-layer use case for Trading Post price refresh. Owns the two distinctly scoped
 * price-refresh variants - the discovery-relevant item set and the profit-relevant item set - via
 * {@link TradingPostPriceRefreshGateway}; item relevance selection, quote fetching and persistence all remain exactly as implemented by
 * {@code sync.TpSync}/its relevance collectors. Performs no calculation itself.
 */
public class TradingPostPriceRefreshService {

    private final TradingPostPriceRefreshGateway gateway;

    public TradingPostPriceRefreshService() {
        this(new TradingPostPriceRefreshGateway());
    }

    /** Seam used by application-layer tests to substitute a fake gateway. */
    public TradingPostPriceRefreshService(TradingPostPriceRefreshGateway gateway) {
        this.gateway = gateway;
    }

    public synchronized void refreshForDiscovery() throws Exception {
        gateway.syncTpPricesForDiscovery();
    }

    public synchronized void refreshForProfit() throws Exception {
        gateway.syncTpPricesForProfit();
    }

    /** Required-ID refresh for one account's calculation; same shared cache and exclusion as the global one. */
    public synchronized void refreshForDiscovery(model.AccountScope scope) throws Exception {
        gateway.syncTpPricesForDiscovery(scope);
    }

    public synchronized void refreshForProfit(model.AccountScope scope) throws Exception {
        gateway.syncTpPricesForProfit(scope);
    }
}
