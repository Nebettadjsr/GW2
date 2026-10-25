package perf;

import java.util.List;

/**
 * Turns {@link DatabaseProbe} readings into KPIs. A probe failure never becomes a number and never
 * throws: every database KPI is then reported unavailable with a unit and the failure as its
 * reason, and the failure is added to the diagnostics.
 */
final class DatabaseMetrics {

    private static final String BOUNDARY = "test schema (pg_stat_all_tables)";

    private DatabaseMetrics() {}

    static void collect(DatabaseProbe db, DatabaseProbe.Counters before, long settleMillis,
                        List<Metric> out, List<String> diagnostics) {
        try {
            Thread.sleep(settleMillis);
            if (before != null) {
                DatabaseProbe.Counters d = db.counters().minus(before);
                out.add(Metric.of("db_rows_inserted", d.rowsInserted(), "rows", BOUNDARY));
                out.add(Metric.of("db_rows_updated", d.rowsUpdated(), "rows", BOUNDARY));
                out.add(Metric.of("db_rows_deleted", d.rowsDeleted(), "rows", BOUNDARY));
                out.add(Metric.of("db_rows_read", d.rowsRead(), "rows", BOUNDARY + ", sequential scan rows + index fetches"));
                out.add(Metric.of("db_transactions_committed", d.transactionsCommitted(), "transactions",
                        "whole test database (pg_stat_database)"));
            } else {
                unavailableCounters(out, "baseline counters could not be read");
            }
            DatabaseProbe.Storage st = db.storage();
            out.add(Metric.of("db_storage_total", st.totalBytes(), "bytes", BOUNDARY + ", tables + indexes + TOAST at workload end"));
            out.add(Metric.of("db_storage_indexes", st.indexBytes(), "bytes", BOUNDARY + ", at workload end"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            diagnostics.add("database statistics unavailable: " + e);
            String reason = "database statistics could not be read: " + e.getClass().getSimpleName();
            for (String n : List.of("db_rows_inserted", "db_rows_updated", "db_rows_deleted", "db_rows_read")) {
                if (out.stream().noneMatch(m -> m.name().equals(n))) out.add(Metric.unavailable(n, "rows", BOUNDARY, reason));
            }
            if (out.stream().noneMatch(m -> m.name().equals("db_transactions_committed"))) {
                out.add(Metric.unavailable("db_transactions_committed", "transactions", "whole test database", reason));
            }
            for (String n : List.of("db_storage_total", "db_storage_indexes")) {
                if (out.stream().noneMatch(m -> m.name().equals(n))) out.add(Metric.unavailable(n, "bytes", BOUNDARY, reason));
            }
        }
        out.add(Metric.unavailable("db_network_bytes", "bytes", "JDBC wire",
                "no per-process network counters are available without OS tooling; HTTP body bytes are reported instead"));
    }

    private static void unavailableCounters(List<Metric> out, String reason) {
        for (String n : List.of("db_rows_inserted", "db_rows_updated", "db_rows_deleted", "db_rows_read")) {
            out.add(Metric.unavailable(n, "rows", BOUNDARY, reason));
        }
        out.add(Metric.unavailable("db_transactions_committed", "transactions", "whole test database", reason));
    }
}
