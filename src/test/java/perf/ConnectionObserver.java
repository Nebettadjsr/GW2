package perf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One persistent, lightweight observation channel for database connection peaks. The connection is
 * opened by the caller outside the timed window and reused for every sample; one query returns the
 * open and the active client-backend counts together. Every session tagged with
 * {@link #APPLICATION_NAME} (this observer and the {@link DatabaseProbe} statistics reads) is
 * excluded from both counts, so observers never count one another. Other clients of the database,
 * fixture writers and the application's own connections are included. Sessions still starting up
 * (no state yet, application name not yet applied) are skipped so a connecting observer is never counted. Peaks are sampled lower
 * bounds. Any failure makes both peaks unavailable and is kept as a diagnostic.
 */
final class ConnectionObserver implements AutoCloseable {

    static final String APPLICATION_NAME = "gw2-perf-observer";

    interface Opener { Connection open() throws SQLException; }

    private static final String SQL = """
            SELECT count(*), count(*) FILTER (WHERE state = 'active') FROM pg_stat_activity
            WHERE datname = current_database() AND backend_type = 'client backend'
              AND pid <> pg_backend_pid() AND state IS NOT NULL AND coalesce(application_name, '') <> ?""";

    private final AtomicLong openPeak = new AtomicLong(-1);
    private final AtomicLong activePeak = new AtomicLong(-1);
    private final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong samples = new AtomicLong();
    private final Connection connection;
    private final PreparedStatement statement;
    private final Thread thread;
    private volatile boolean running = true;

    /** Opens the single connection now; a failure is recorded and leaves the observer unavailable. */
    ConnectionObserver(Opener opener, long intervalMillis) {
        Connection c = null;
        PreparedStatement ps = null;
        try {
            c = opener.open();
            ps = c.prepareStatement(SQL);
            ps.setString(1, APPLICATION_NAME);
        } catch (Exception e) {
            failures.add("connection observer could not open: " + e);
        }
        connection = c;
        statement = ps;
        if (statement == null) {
            thread = null;
            return;
        }
        sample();
        thread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    return;
                }
                sample();
            }
        }, "perf-connection-observer");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void sample() {
        if (!failures.isEmpty()) return;
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            openPeak.accumulateAndGet(rs.getLong(1), Math::max);
            activePeak.accumulateAndGet(rs.getLong(2), Math::max);
            samples.incrementAndGet();
        } catch (SQLException e) {
            failures.add("connection observation failed: " + e);
        }
    }

    /** Takes a last sample and stops. */
    @Override
    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
        if (statement != null) sample();
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
            // the observer is finished either way
        }
    }

    long samples() { return samples.get(); }

    List<String> failures() { return List.copyOf(failures); }

    /** The two connection KPIs, or unavailable metrics with the failure as reason (also added to diagnostics). */
    List<Metric> metrics(List<String> diagnostics, long intervalMillis) {
        String boundary = "database-wide pg_stat_activity (client backends of the test database, not only this schema); "
                + "one persistent observer session is excluded, as are the statistics probe sessions; other database clients, "
                + "fixture writers and the observer's own small query load are not excluded";
        if (!failures.isEmpty() || samples.get() == 0) {
            String reason = failures.isEmpty() ? "no connection sample succeeded" : failures.get(0);
            diagnostics.addAll(failures);
            return List.of(
                    Metric.unavailable("db_connections_open_peak", "connections", "database", reason),
                    Metric.unavailable("db_connections_active_peak", "connections", "database", reason));
        }
        String sampled = ", sampled every " + intervalMillis + " ms (lower bound)";
        return List.of(
                Metric.of("db_connections_open_peak", openPeak.get(), "connections",
                        boundary + "; counts every state including idle" + sampled),
                Metric.of("db_connections_active_peak", activePeak.get(), "connections",
                        boundary + "; only state 'active', point samples so short statements are mostly missed" + sampled));
    }
}
