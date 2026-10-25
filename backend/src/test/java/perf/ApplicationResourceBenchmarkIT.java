package perf;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STORY-PERF-005 full measurement run. Informational only: the workloads record failures as
 * diagnostics in the report and this test asserts no performance threshold. Excluded from
 * {@code ./mvnw test} by its {@code IT} suffix.
 *
 * <p>Run: {@code ./mvnw test -Dtest=ApplicationResourceBenchmarkIT -DfailIfNoSpecifiedTests=false}
 * with {@code DATABASE_URL}, {@code DATABASE_USER}, {@code DATABASE_PASSWORD} and {@code GW2_API_KEY}
 * (any placeholder) set. Writes {@code application-resources.json} and {@code .md} under
 * {@code target/perf} (override with {@code -Dgw2.perf.output-dir=...}). See docs/PERFORMANCE_MEASUREMENTS.md.
 */
class ApplicationResourceBenchmarkIT extends ApplicationBenchmarkHarness {

    @Override
    Sizes sizes() {
        return new Sizes(400, 200, 5_000, 2_000, 25, 5, 8, 24, 5_000);
    }

    @Test
    void measureApplicationWorkloads() throws Exception {
        MeasurementReport report = runAll();
        Path dir = Path.of(System.getProperty("gw2.perf.output-dir", "target/perf"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("application-resources.json"), report.toJsonString());
        Files.writeString(dir.resolve("application-resources.md"), report.toMarkdown());
        System.out.println(report.toMarkdown());
    }
}
