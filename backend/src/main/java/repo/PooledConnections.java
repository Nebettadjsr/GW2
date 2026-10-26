package repo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Process-owned, bounded JDBC connections; callers return a lease with Connection.close(). */
final class PooledConnections implements AutoCloseable {
    private final HikariDataSource pool;
    private final String searchPath;

    PooledConnections(String url, String user, String password, int size, long timeoutMs) throws SQLException {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(timeoutMs);
        config.setPoolName("gw2-jdbc");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Preserve the role/URL's entire search_path, including multiple schemas and "$user".
        // Hikari's schema reset alone cannot restore that after a test overrides setSchema().
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW search_path")) {
            rows.next();
            searchPath = rows.getString(1);
        }
        pool = new HikariDataSource(config);
    }

    Connection open(String schema) throws SQLException {
        Connection connection = pool.getConnection();
        try {
            if (schema != null && !schema.isBlank()) connection.setSchema(schema);
            else try (var statement = connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
                statement.setString(1, searchPath);
                statement.execute();
            }
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    @Override public void close() { pool.close(); }
}
