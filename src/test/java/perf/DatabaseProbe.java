package perf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database activity and storage of one disposable schema, read from PostgreSQL's own statistics.
 * Row counters are per schema (so unrelated databases and schemas are excluded); connection counts
 * are per database because PostgreSQL has no per-schema connection. The statistics collector
 * publishes with a delay of up to about a second, hence {@link #SETTLE_MILLIS}.
 */
public final class DatabaseProbe {

    /** PostgreSQL flushes backend statistics roughly every second; wait this long before a closing read. */
    public static final long SETTLE_MILLIS = 1_300;

    private final String url;
    private final String user;
    private final String password;
    private final String schema;

    public DatabaseProbe(String url, String user, String password, String schema) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.schema = schema;
    }

    /** Cumulative per-schema counters at one instant. */
    public record Counters(long rowsInserted, long rowsUpdated, long rowsDeleted, long rowsRead,
                           long transactionsCommitted) {
        public Counters minus(Counters before) {
            return new Counters(rowsInserted - before.rowsInserted, rowsUpdated - before.rowsUpdated,
                    rowsDeleted - before.rowsDeleted, rowsRead - before.rowsRead,
                    transactionsCommitted - before.transactionsCommitted);
        }
    }

    /** Schema storage: heap plus TOAST, indexes, and the sum of both ({@code pg_total_relation_size}). */
    public record Storage(long tableBytes, long indexBytes, long totalBytes, long rows) {}

    public Counters counters() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT coalesce(sum(n_tup_ins), 0), coalesce(sum(n_tup_upd), 0), coalesce(sum(n_tup_del), 0),
                            coalesce(sum(coalesce(seq_tup_read, 0) + coalesce(idx_tup_fetch, 0)), 0),
                            (SELECT xact_commit FROM pg_stat_database WHERE datname = current_database())
                     FROM pg_stat_all_tables WHERE schemaname = ?""")) {
            c.createStatement().execute("SELECT pg_stat_clear_snapshot()");
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Counters(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
            }
        }
    }

    public Storage storage() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT coalesce(sum(pg_table_size(cl.oid)), 0), coalesce(sum(pg_indexes_size(cl.oid)), 0),
                            coalesce(sum(pg_total_relation_size(cl.oid)), 0),
                            coalesce(sum(cl.reltuples::bigint) FILTER (WHERE cl.reltuples > 0), 0)
                     FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
                     WHERE n.nspname = ? AND cl.relkind = 'r'""")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Storage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
            }
        }
    }

    /** Opens a connection tagged as an observer session so connection sampling excludes it. */
    public Connection openObserverConnection() throws SQLException {
        return connect();
    }

    private Connection connect() throws SQLException {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", ConnectionObserver.APPLICATION_NAME);
        return DriverManager.getConnection(url, props);
    }

    /** Server version string, for the environment block. */
    public String serverVersion() throws SQLException {
        try (Connection c = connect()) {
            return c.getMetaData().getDatabaseProductVersion();
        }
    }
}
