package repo;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PooledConnectionsTest {
    private PooledConnections pool(int size) throws SQLException {
        return new PooledConnections(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS, size, 500);
    }

    @Test void reusesPhysicalConnectionAndResetsTransactionsAndSessionState() throws Exception {
        try (var pool = pool(1)) {
            int pid;
            try (var connection = pool.open(null)) {
                pid = scalar(connection, "SELECT pg_backend_pid()");
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.createStatement().execute("CREATE TEMP TABLE should_rollback (id integer)");
            }
            try (var connection = pool.open(null)) {
                assertEquals(pid, scalar(connection, "SELECT pg_backend_pid()"));
                assertTrue(connection.getAutoCommit());
                assertFalse(connection.isReadOnly());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
                try (var rows = connection.createStatement().executeQuery("SELECT to_regclass('should_rollback')")) {
                    assertTrue(rows.next());
                    assertNull(rows.getString(1));
                }
                connection.setReadOnly(true);
            }
            try (var connection = pool.open(null)) { assertFalse(connection.isReadOnly()); }
        }
    }

    @Test void preservesConfiguredSearchPathAfterSchemaOverride() throws Exception {
        String schema = "pool_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS)) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            String url = AppConfig.DB_URL + (AppConfig.DB_URL.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
            try (var pool = new PooledConnections(url, AppConfig.DB_USER, AppConfig.DB_PASS, 1, 500)) {
                try (var connection = pool.open("public")) { assertEquals("public", connection.getSchema()); }
                try (var connection = pool.open(null);
                     var rows = connection.createStatement().executeQuery("SHOW search_path")) {
                    assertEquals(schema, connection.getSchema());
                    assertTrue(rows.next());
                    assertEquals(schema + ",public", rows.getString(1));
                }
            } finally { admin.createStatement().execute("DROP SCHEMA " + schema); }
        }
    }

    @Test void exhaustedPoolWaitsBoundedlyAndRecoversWhenLeaseReturns() throws Exception {
        try (var pool = pool(1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var held = pool.open(null)) {
                var waiting = executor.submit(() -> assertThrows(SQLException.class, () -> pool.open(null)));
                waiting.get(5, TimeUnit.SECONDS);
                assertFalse(held.isClosed());
            }
            try (var connection = pool.open(null)) { assertEquals(1, scalar(connection, "SELECT 1")); }
        }
    }

    @Test void replacesBrokenConnection() throws Exception {
        try (var pool = pool(1)) {
            int oldPid;
            try (var connection = pool.open(null);
                 var admin = DriverManager.getConnection(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS)) {
                oldPid = scalar(connection, "SELECT pg_backend_pid()");
                admin.createStatement().execute("SELECT pg_terminate_backend(" + oldPid + ")");
                assertThrows(SQLException.class, () -> scalar(connection, "SELECT 1"));
            }
            try (var connection = pool.open(null)) {
                assertNotEquals(oldPid, scalar(connection, "SELECT pg_backend_pid()"));
                assertEquals(1, scalar(connection, "SELECT 1"));
            }
        }
    }

    @Test void closeReleasesConnectionsAndRefusesNewLeases() throws Exception {
        var pool = pool(1);
        try (var ignored = pool.open(null)) { }
        pool.close();
        assertThrows(SQLException.class, () -> pool.open(null));
    }

    private int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
