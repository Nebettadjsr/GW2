package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable result of one measurement run (STORY-PERF-005 AC3). Reports are comparable only
 * when {@code schemaVersion}, {@code fixtureVersion} and the environment's comparison-relevant
 * fields match. Everything here is a <em>process</em> measurement; container measurements are
 * declared unavailable until container infrastructure exists.
 */
public final class MeasurementReport {

    public static final String SCHEMA_VERSION = "1";
    public static final String MEASUREMENT_SCOPE = "application-process";
    public static final String CONTAINER_NOTE =
            "No container infrastructure exists yet; container CPU/memory, startup-to-readiness and image size are not measured.";

    /** One workload's labeled results. {@code diagnostics} carries errors and caveats; none is a gate. */
    public record Workload(String name, String description, String boundary, int iterations,
                           List<Metric> metrics, List<String> limits, List<String> diagnostics) {
        public Workload {
            metrics = List.copyOf(metrics);
            limits = List.copyOf(limits);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final String commit;
    private final Instant timestamp;
    private final String fixtureVersion;
    private final Map<String, String> environment;
    private final List<Workload> workloads = new ArrayList<>();

    public MeasurementReport(String commit, Instant timestamp, String fixtureVersion, Map<String, String> environment) {
        this.commit = require(commit, "commit");
        this.timestamp = timestamp;
        this.fixtureVersion = require(fixtureVersion, "fixtureVersion");
        this.environment = new LinkedHashMap<>(environment);
    }

    private static String require(String v, String what) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(what + " is required");
        return v;
    }

    public void add(Workload workload) {
        workloads.add(workload);
    }

    public List<Workload> workloads() {
        return List.copyOf(workloads);
    }

    public ObjectNode toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("commit", commit);
        root.put("timestamp", timestamp.toString());
        root.put("fixtureVersion", fixtureVersion);
        root.put("measurementScope", MEASUREMENT_SCOPE);
        root.put("containerMeasurements", CONTAINER_NOTE);
        root.put("informationalOnly", true);
        ObjectNode env = root.putObject("environment");
        environment.forEach(env::put);
        ArrayNode list = root.putArray("workloads");
        for (Workload w : workloads) {
            ObjectNode node = list.addObject();
            node.put("name", w.name());
            node.put("description", w.description());
            node.put("boundary", w.boundary());
            node.put("iterations", w.iterations());
            ArrayNode metrics = node.putArray("metrics");
            w.metrics().forEach(m -> metrics.add(m.toJson(mapper)));
            ArrayNode limits = node.putArray("limits");
            w.limits().forEach(limits::add);
            ArrayNode diagnostics = node.putArray("diagnostics");
            w.diagnostics().forEach(diagnostics::add);
        }
        return root;
    }

    public String toJsonString() {
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(toJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Markdown summary for a job summary or the PR: unavailable KPIs show their reason, never a number. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Application resource measurements (informational)\n\n");
        sb.append("- Commit: `").append(commit).append("`\n- Timestamp: ").append(timestamp)
                .append("\n- Fixture version: `").append(fixtureVersion).append("`\n- Scope: ")
                .append(MEASUREMENT_SCOPE).append(" (").append(CONTAINER_NOTE).append(")\n");
        environment.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        for (Workload w : workloads) {
            sb.append("\n## ").append(w.name()).append("\n\n").append(w.description()).append("\n\n")
                    .append("| KPI | Value | Unit | Boundary |\n|---|---|---|---|\n");
            for (Metric m : w.metrics()) {
                sb.append("| ").append(m.name()).append(" | ")
                        .append(m.available() ? format(m.value()) : "unavailable: " + m.unavailableReason())
                        .append(" | ").append(m.unit() == null ? "" : m.unit()).append(" | ")
                        .append(m.boundary()).append(" |\n");
            }
            w.limits().forEach(l -> sb.append("\n- Limit: ").append(l));
            w.diagnostics().forEach(d -> sb.append("\n- Diagnostic: ").append(d));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String format(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
