package application;

import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.sql.Timestamp;

/** Read-only freshness summary for the shared Trading Post price cache. */
public class TradingPostPriceCacheStatusService {
    private final TpPriceRepository repository;

    public TradingPostPriceCacheStatusService() {
        this(new TpPriceRepository());
    }

    public TradingPostPriceCacheStatusService(TpPriceRepository repository) {
        this.repository = repository;
    }

    public TpPriceRepository.CacheStatus read() throws SQLException {
        return repository.cacheStatus();
    }
}
