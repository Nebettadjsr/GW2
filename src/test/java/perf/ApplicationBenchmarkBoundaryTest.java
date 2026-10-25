package perf;

import org.junit.jupiter.api.Test;

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
class ApplicationBenchmarkBoundaryTest extends ApplicationBenchmarkHarness {

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
