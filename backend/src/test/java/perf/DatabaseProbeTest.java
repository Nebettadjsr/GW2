package perf;

import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A generic statistics read must be observationally neutral: it may not grow the application pool. */
class DatabaseProbeTest {

    private static long untaggedClients(DatabaseProbe probe) throws Exception {
        try (Connection c = probe.openObserverConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                             + "AND backend_type = 'client backend' AND coalesce(application_name, '') <> '"
                             + ConnectionObserver.APPLICATION_NAME + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void genericStatisticsReadsDoNotGrowTheApplicationPoolButTheOptInFlushDoes() throws Exception {
        DatabaseProbe probe = new DatabaseProbe(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"), "kpi_probe_neutrality_unused");
        try (Connection warm = repo.Db.open()) {
            warm.createStatement().execute("SELECT 1");
        }
        long before = untaggedClients(probe);
        assertTrue(before >= 1, "the application pool holds its warmed connection");
        probe.counters();
        probe.storage();
        assertEquals(before, untaggedClients(probe), "a generic read must not borrow or open application connections");

        if (repo.AppConfig.DB_POOL_SIZE > 1) {
            probe.withApplicationPoolFlush().counters();
            assertTrue(untaggedClients(probe) > before, "the opt-in flush is the pool-expanding path (guard is meaningful)");
        }
    }
}
