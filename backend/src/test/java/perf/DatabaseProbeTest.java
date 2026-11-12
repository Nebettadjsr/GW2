package perf;

import org.junit.jupiter.api.Test;
import repo.EnvConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A generic statistics read must be observationally neutral: it may not grow the application pool. */
class DatabaseProbeTest {

    // Test-only access keeps pool instrumentation out of the production API. This class owns the
    // pool for its duration; unrelated database clients never enter the observed count.
    private static void resetPool() throws Exception {
        var close = repo.Db.class.getDeclaredMethod("closePool");
        close.setAccessible(true);
        close.invoke(null);
    }

    private static HikariPoolMXBean metrics() throws Exception {
        var holder = repo.Db.class.getDeclaredField("connections");
        holder.setAccessible(true);
        Object connections = holder.get(null);
        var field = connections.getClass().getDeclaredField("pool");
        field.setAccessible(true);
        return ((HikariDataSource) field.get(connections)).getHikariPoolMXBean();
    }

    @Test
    void genericReadsPreserveBothWarmedAndFullPoolsWhileOnlyTheOptInFlushExpands() throws Exception {
        DatabaseProbe probe = new DatabaseProbe(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"), "kpi_probe_neutrality_unused");
        resetPool();
        try {
            try (Connection warm = repo.Db.open(); var statement = warm.createStatement()) {
                statement.execute("SELECT 1");
            }
            HikariPoolMXBean pool = metrics();
            assertEquals(1, pool.getTotalConnections(), "controlled lazy pool starts with one used connection");
            probe.counters();
            probe.storage();
            assertEquals(1, pool.getTotalConnections(), "generic reads must not fill a partially populated pool");

            DatabaseProbe explicit = new DatabaseProbe(EnvConfig.require("DATABASE_URL"),
                    EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"),
                    "kpi_probe_neutrality_unused").withApplicationPoolFlush();
            explicit.counters();
            assertEquals(repo.AppConfig.DB_POOL_SIZE, pool.getTotalConnections(), "explicit flush may fill the pool");

            probe.counters();
            probe.storage();
            explicit.counters();
            assertEquals(repo.AppConfig.DB_POOL_SIZE, pool.getTotalConnections(),
                    "reads and repeated explicit flushes must also work when the pool was already full");
        } finally {
            resetPool();
        }
    }
}
