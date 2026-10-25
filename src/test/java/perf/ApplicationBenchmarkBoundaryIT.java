package perf;

import org.junit.jupiter.api.Test;

import repo.EnvConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies, at a tiny size and with no timing assertion, that each benchmark workload really
 * reaches the application boundary it names (STORY-PERF-005 required tests): tasks finish, the
 * database counters move, profit returns calculated rows, and the report is well formed.
 * A benchmark never substitutes for behavior tests.
 */
class ApplicationBenchmarkBoundaryIT extends ApplicationBenchmarkHarness {

    @Override
    Sizes sizes() {
        return new Sizes(12, 6, 30, 20, 2, 1, 2, 4, 200);
    }

    private static double value(MeasurementReport.Workload w, String metric) {
        return w.metrics().stream().filter(m -> m.name().equals(metric)).findFirst()
                .orElseThrow(() -> new AssertionError(w.name() + " lacks " + metric)).value();
    }

    private static MeasurementReport.Workload workload(MeasurementReport r, String name) {
        return r.workloads().stream().filter(w -> w.name().equals(name)).findFirst().orElseThrow();
    }

    private static long untaggedClients(DatabaseProbe probe) throws Exception {
        try (java.sql.Connection c = probe.openObserverConnection();
             java.sql.ResultSet rs = c.createStatement().executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                             + "AND backend_type = 'client backend' AND coalesce(application_name, '') <> '"
                             + ConnectionObserver.APPLICATION_NAME + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void connectionObserverUsesOneSessionAndNeverCountsItselfOrTheProbes() throws Exception {
        String url = EnvConfig.require("DATABASE_URL");
        DatabaseProbe probe = new DatabaseProbe(url, EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"), "unused");
        try (java.sql.Connection neighbour = java.sql.DriverManager.getConnection(url,
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
             ConnectionObserver observer = new ConnectionObserver(probe::openObserverConnection, 5)) {
            long before = untaggedClients(probe);
            probe.counters();
            probe.storage();
            Thread.sleep(200);
            assertTrue(observer.samples() >= 3, "sampled repeatedly");
            assertTrue(observer.failures().isEmpty(), observer.failures().toString());
            try (java.sql.Connection c = probe.openObserverConnection();
                 java.sql.ResultSet rs = c.createStatement().executeQuery(
                         "SELECT count(*), count(DISTINCT pid) FROM pg_stat_activity WHERE application_name = '"
                                 + ConnectionObserver.APPLICATION_NAME + "' AND datname = current_database()")) {
                rs.next();
                // This test's own session is tagged too; the observer is the only other one: no per-sample connections.
                assertEquals(2, rs.getLong(1));
            }
            java.util.List<String> diagnostics = new java.util.ArrayList<>();
            java.util.List<Metric> metrics = observer.metrics(diagnostics, 5);
            assertTrue(diagnostics.isEmpty());
            for (Metric m : metrics) assertTrue(m.available(), m.name());
            // Independent ground truth from this test's own (tagged) session: untagged client sessions before and after.
            long after = untaggedClients(probe);
            long lo = Math.min(before, after);
            long hi = Math.max(before, after);
            // The untagged extra connection guarantees at least one; counting the observer or the probes would exceed hi.
            assertTrue(hi >= 1);
            assertTrue(metrics.get(0).value() >= lo && metrics.get(0).value() <= hi,
                    "open peak " + metrics.get(0).value() + " outside independent count " + lo + ".." + hi);
            assertTrue(metrics.get(1).value() <= metrics.get(0).value());
        }
    }

    @Test
    void everyWorkloadReachesItsApplicationBoundary() throws Exception {
        MeasurementReport report = runAll();
        assertEquals(10, report.workloads().size());
        for (MeasurementReport.Workload w : report.workloads()) {
            assertTrue(w.diagnostics().isEmpty(), w.name() + " diagnostics: " + w.diagnostics());
        }

        // Account refresh persisted 20 bank slots + 12 materials + recipes + Luck; the repeat rewrote rows.
        MeasurementReport.Workload full = workload(report, "account_sync_full");
        assertTrue(value(full, "db_rows_inserted") >= 20 + 12, "full sync inserts the fixture rows");
        assertTrue(value(full, "background_job_duration") > 0);
        // Submission plus at least the terminal status poll are recorded in the same HTTP figures.
        assertTrue(value(full, "http_requests") >= 2, "task polls are counted");
        assertTrue(value(full, "network_egress") > 0);
        MeasurementReport.Workload incremental = workload(report, "account_sync_incremental");
        assertTrue(value(incremental, "db_rows_inserted") + value(incremental, "db_rows_updated") > 0,
                "incremental sync writes through the same boundary");

        // Price refresh writes one row per fixture item (12 raw + 30 outputs), all of which already exist.
        assertEquals(42, value(workload(report, "price_refresh"), "db_rows_updated"));

        // Global refresh adds the 24 recipes that were not seeded; profit then covers all 30.
        assertTrue(value(workload(report, "global_refresh"), "db_rows_inserted") >= 24);
        assertEquals(0, value(workload(report, "profit_small"), "http_errors"));
        assertTrue(value(workload(report, "profit_small"), "result_rows") > 0);
        // Rows alone prove nothing (all could be RECIPE_NOT_ALLOWED): real crafts must have been calculated.
        assertTrue(value(workload(report, "profit_small"), "craftable_rows") > 0);
        assertTrue(value(workload(report, "profit_large"), "craftable_rows")
                >= value(workload(report, "profit_small"), "craftable_rows"));
        assertTrue(value(workload(report, "profit_large"), "result_rows")
                >= value(workload(report, "profit_small"), "result_rows"));

        assertEquals(8, value(workload(report, "http_typical"), "http_requests"));
        assertEquals(8, value(workload(report, "http_concurrent"), "http_requests"));
        MeasurementReport.Workload reads = workload(report, "database_reads");
        assertTrue(value(reads, "db_rows_read") > 0);
        assertEquals(2, value(reads, "repository_read_rounds"));
        assertTrue(value(reads, "repository_read_latency_p95") >= 0);
        assertTrue(value(reads, "repository_read_throughput") > 0);
        // A no-HTTP workload carries no HTTP KPIs; HTTP workloads carry no repository KPIs.
        assertTrue(reads.metrics().stream().noneMatch(m -> m.name().startsWith("http_") || m.name().startsWith("network_")));
        assertTrue(workload(report, "http_typical").metrics().stream().noneMatch(m -> m.name().startsWith("repository_")));
        assertTrue(value(workload(report, "http_typical"), "http_latency_p95") > 0);
        for (MeasurementReport.Workload w : report.workloads()) {
            assertTrue(w.metrics().stream().anyMatch(m -> m.name().equals("db_connections_open_peak")), w.name());
            assertTrue(w.metrics().stream().anyMatch(m -> m.name().equals("db_connections_active_peak")), w.name());
        }

        // Every workload carries the process KPIs; idle reports no HTTP at all.
        for (MeasurementReport.Workload w : report.workloads()) {
            assertTrue(w.metrics().stream().anyMatch(m -> m.name().equals("cpu_time")), w.name());
            assertTrue(w.metrics().stream().anyMatch(m -> m.name().equals("db_storage_total")), w.name());
        }
        assertFalse(workload(report, "idle").metrics().stream().anyMatch(m -> m.name().equals("http_requests")));
        List<String> names = report.toJson().findValuesAsText("name");
        assertTrue(names.contains("network_ingress") && names.contains("db_network_bytes"));
    }
}
