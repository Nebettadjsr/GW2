package application;

import sync.TradingPostPriceRefreshGateway;

/**
 * Application-layer use case for Trading Post price refresh (TARGET_ARCHITECTURE.md §8,
 * STORY-APP-006). Owns the two pre-existing, distinctly scoped price-refresh variants -
 * {@code CraftingDiscoveryView}'s discovery-relevant item set and {@code CraftingProfitView}'s
 * profit-relevant item set - via {@link TradingPostPriceRefreshGateway}; item relevance
 * selection, quote fetching and persistence all remain exactly as implemented by
 * {@code sync.TpSync}/its relevance collectors. Holds no JavaFX dependency and performs no
 * calculation itself.
 */
public class TradingPostPriceRefreshService {

    private final TradingPostPriceRefreshGateway gateway;

    public TradingPostPriceRefreshService() {
        this(new TradingPostPriceRefreshGateway());
    }

    /** Seam used by application-layer tests to substitute a fake gateway (TARGET_ARCHITECTURE.md §25). */
    public TradingPostPriceRefreshService(TradingPostPriceRefreshGateway gateway) {
        this.gateway = gateway;
    }

    public void refreshForDiscovery() throws Exception {
        gateway.syncTpPricesForDiscovery();
    }

    public void refreshForProfit() throws Exception {
        gateway.syncTpPricesForProfit();
    }
}
