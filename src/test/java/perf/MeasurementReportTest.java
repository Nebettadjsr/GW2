package perf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STORY-PERF-005: report math, units, metadata and unavailable-value handling against known sample data. */
class MeasurementReportTest {

    @Test
    void p95IsNearestRankOverKnownSamples() {
        // 1..20: ceil(0.95 * 20) = 19th smallest.
        List<Double> twenty = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> (double) i).toList();
        assertEquals(19.0, Stats.p95(twenty));
        // Order must not matter, and a single sample is its own p95.
        assertEquals(19.0, Stats.p95(twenty.reversed()));
        assertEquals(7.5, Stats.p95(List.of(7.5)));
        // 10 samples: ceil(9.5) = 10th, so the maximum.
        assertEquals(100.0, Stats.p95(List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)));
        assertEquals(50.0, Stats.percentile(List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), 50));
        assertThrows(IllegalArgumentException.class, () -> Stats.p95(List.of()));
    }

    @Test
    void throughputAndCpuUtilizationFollowTheirDefinitions() {
        // 50 requests in 2 s.
        assertEquals(25.0, Stats.throughputPerSecond(50, 2_000_000_000L));
        // 3 CPU-seconds in 2 wall-seconds on 4 processors is 37.5% of the machine.
        assertEquals(37.5, Stats.cpuUtilizationPercent(3_000_000_000L, 2_000_000_000L, 4), 1e-9);
        assertEquals(1.5, Stats.nanosToMillis(1_500_000));
        assertThrows(IllegalArgumentException.class, () -> Stats.throughputPerSecond(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Stats.cpuUtilizationPercent(1, 1, 0));
    }

    @Test
    void procStatusIsParsedIntoBytesAndAbsentKeysAreUnavailable() {
        List<String> status = List.of("Name:\tjava", "VmHWM:\t  2048 kB", "VmRSS:\t   1024 kB");
        assertEquals(1024L * 1024, ProcessProbe.parseStatusKb(status, "VmRSS:"));
        assertEquals(2048L * 1024, ProcessProbe.parseStatusKb(status, "VmHWM:"));
        assertEquals(-1, ProcessProbe.parseStatusKb(status, "VmSwap:"));
    }

    @Test
    void peakSamplerKeepsTheMaximumAndReportsUnavailableWhenNothingCouldBeRead() throws Exception {
        long[] values = {5, 90, 7};
        int[] next = {0};
        try (ProcessProbe.PeakSampler sampler = new ProcessProbe.PeakSampler("test",
                () -> values[Math.min(next[0]++, values.length - 1)], 1)) {
            Thread.sleep(50);
            assertEquals(90, sampler.peak());
        }
        try (ProcessProbe.PeakSampler failing = new ProcessProbe.PeakSampler("failing",
                () -> { throw new IllegalStateException("no source"); }, 1)) {
            assertEquals(-1, failing.peak());
        }
    }

    @Test
    void metricRequiresExactlyAValueOrAReason() {
        assertThrows(IllegalArgumentException.class, () -> new Metric("x", null, "ms", "b", null));
        assertThrows(IllegalArgumentException.class, () -> new Metric("x", 1.0, "ms", "b", "also a reason"));
        assertThrows(IllegalArgumentException.class, () -> Metric.of("x", Double.NaN, "ms", "b"));
        assertThrows(IllegalArgumentException.class, () -> Metric.of("x", 1.0, "", "b"));
        assertThrows(IllegalArgumentException.class, () -> Metric.of("x", 1.0, "ms", " "));
        assertFalse(Metric.unavailable("x", "ms", "b", "no counter").available());
    }

    private MeasurementReport sample() {
        MeasurementReport report = new MeasurementReport("abc123", Instant.parse("2026-01-02T03:04:05Z"),
                "fixtures-9", Map.of("java", "25", "os", "linux"));
        report.add(new MeasurementReport.Workload("w", "desc", "HTTP boundary", 3, List.of(
                Metric.of("http_latency_p95", 19.0, "ms", "client"),
                Metric.unavailable("network_egress", "bytes", "wire", "no counters")),
                List.of("synthetic data"), List.of("one request failed")));
        return report;
    }

    @Test
    void jsonCarriesMetadataUnitsBoundariesAndExplicitUnavailability() {
        JsonNode json = sample().toJson();
        assertEquals("abc123", json.path("commit").asText());
        assertEquals("2026-01-02T03:04:05Z", json.path("timestamp").asText());
        assertEquals("fixtures-9", json.path("fixtureVersion").asText());
        assertEquals(MeasurementReport.SCHEMA_VERSION, json.path("schemaVersion").asText());
        assertEquals("application-process", json.path("measurementScope").asText());
        assertTrue(json.path("containerMeasurements").asText().contains("not measured"));
        assertEquals("linux", json.path("environment").path("os").asText());
        assertTrue(json.path("informationalOnly").asBoolean());

        JsonNode metrics = json.path("workloads").get(0).path("metrics");
        assertEquals(19.0, metrics.get(0).path("value").asDouble());
        assertEquals("ms", metrics.get(0).path("unit").asText());
        assertEquals("client", metrics.get(0).path("boundary").asText());
        JsonNode missing = metrics.get(1);
        assertTrue(missing.path("value").isNull(), "an unavailable KPI has no number");
        assertEquals("no counters", missing.path("unavailableReason").asText());
        assertEquals("one request failed", json.path("workloads").get(0).path("diagnostics").get(0).asText());
    }

    @Test
    void markdownShowsReasonsInsteadOfNumbersForUnavailableKpis() {
        String md = sample().toMarkdown();
        assertTrue(md.contains("| http_latency_p95 | 19 | ms | client |"), md);
        assertTrue(md.contains("| network_egress | unavailable: no counters | bytes | wire |"), md);
        assertTrue(md.contains("`abc123`") && md.contains("fixtures-9"));
        assertTrue(md.contains("Diagnostic: one request failed"));
    }

    @Test
    void reportRequiresCommitAndFixtureVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementReport(" ", Instant.now(), "f", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementReport("c", Instant.now(), null, Map.of()));
    }
}
