package application;

import org.junit.jupiter.api.Test;
import repo.tp.TpPriceRepository;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingPostPriceCacheStatusServiceTest {
    @Test
    void repositoryFailureBecomesDataStoreException() {
        var failure = new java.sql.SQLException("simulated cache status failure");
        var repository = new TpPriceRepository() {
            @Override public CacheStatus cacheStatus() throws java.sql.SQLException { throw failure; }
        };
        var thrown = assertThrows(DataStoreException.class,
                () -> new TradingPostPriceCacheStatusService(repository).read());
        assertSame(failure, thrown.getCause());
    }
}
