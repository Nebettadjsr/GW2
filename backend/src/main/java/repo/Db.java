package repo;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared JDBC connection helper for both the {@code repo} and {@code sync} packages
 * (STORY-INFRA-003; previously duplicated as {@code sync.Db}).
 */
public final class Db {
    private Db() {}
    private static PooledConnections connections;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Db::closePool, "jdbc-pool-close"));
    }

    /**
     * Optional system property (never a normal environment/.env value) letting an in-process
     * UI test point every {@code Db.open()} call - including the ones made deep inside view
     * controllers that have no injectable {@link Connection} - at a disposable schema instead of
     * the developer's normal database (STORY-UI-001, docs/TEST_STRATEGY.md).
     * Unset in normal/production use, so behavior is unchanged: {@link #open()} still returns a
     * connection on the default schema exactly as before this property existed.
     */
    public static final String TEST_SCHEMA_PROPERTY = "gw2tool.test.schema";

    public static Connection open() throws SQLException {
        return pool().open(System.getProperty(TEST_SCHEMA_PROPERTY));
    }

    // JDBC I/O must not run in a static initializer: that pins a virtual thread's carrier.
    private static synchronized PooledConnections pool() throws SQLException {
        if (connections == null) connections = new PooledConnections(AppConfig.DB_URL,
                AppConfig.DB_USER, AppConfig.DB_PASS, AppConfig.DB_POOL_SIZE, AppConfig.DB_POOL_TIMEOUT_MS);
        return connections;
    }

    static synchronized void closePool() {
        if (connections != null) {
            connections.close();
            connections = null;
        }
    }
}
