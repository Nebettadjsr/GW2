package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Versioned JSON/Markdown summary plus a checksummed companion JSONL holding every sample
 * losslessly (STORY-KPI-002). Contains no GUIDs, names, keys, cookies, bodies or raw input paths:
 * {@link CapacitySample} has no such fields and metadata values are rejected if they look like paths/secrets.
 */
public final class CapacityReport {
    public static final String SCHEMA_VERSION = "capacity-1";
    public static final String SAMPLE_SCHEMA = "capacity-sample-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CapacityConfig config;
    private final CapacityOutcome outcome;
    private final Map<String, String> metadata;

    /** @param metadata disclosed identities: commit, dirty, fixture/prepared/shared/expected ids, hardware, generator boundary, resource availability. */
    public CapacityReport(CapacityConfig config, CapacityOutcome outcome, Map<String, String> metadata) {
        this.config = config;
        this.outcome = outcome;
        this.metadata = new LinkedHashMap<>(metadata);
        for (String v : this.metadata.values()) {
            if (looksLikePrivatePath(v)) {
                throw new IllegalArgumentException("metadata must not contain raw filesystem paths");
            }
        }
    }

    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /** POSIX absolute/home paths, Windows drive paths with either slash, UNC and any backslash path. */
    static boolean looksLikePrivatePath(String v) {
        if (v == null) return false;
        String t = v.strip();
        return t.startsWith("/") || t.startsWith("~") || t.contains("\\")
                || t.matches("(?is)^[a-z]:.*") && t.length() > 1 && (t.charAt(2 - 0 > t.length() - 1 ? 1 : 2) == '/' || t.length() == 2)
                || t.matches("(?s).*(^|[\\s=,;\"'(])/(home|Users|root|tmp|var|etc|mnt|opt)/.*");
    }

    static String requireSafeArtifactId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.contains("..")) {
            throw new IllegalArgumentException("companion artifact id must be a safe public identifier");
        }
        return id;
    }

    public List<StageObservation> allStages() {
        List<StageObservation> all = new ArrayList<>();
        all.addAll(outcome.baseline());
        all.addAll(outcome.warmup());
        all.addAll(outcome.measured());
        return all;
    }

    /** Lossless JSONL: one line per sample, stage identity included, in stage order. */
    public String samplesJsonl() {
        StringBuilder sb = new StringBuilder();
        int stageIndex = 0;
        for (StageObservation st : allStages()) {
            for (CapacitySample s : st.samples()) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("schema", SAMPLE_SCHEMA);
                n.put("stageIndex", stageIndex);
                n.put("role", st.role());
                n.put("level", st.level());
                n.put("seed", st.seed());
                n.put("journey", s.journey());
                n.put("kind", s.kind());
                n.put("user", s.user());
                n.put("scheduledOffsetMillis", s.scheduledOffsetMillis());
                n.put("startDelayMillis", s.startDelayMillis());
                n.put("durationMillis", s.durationMillis());
                if (s.firstUsableMillis() == null) n.putNull("firstUsableMillis"); else n.put("firstUsableMillis", s.firstUsableMillis());
                n.put("finalUsable", s.finalUsable());
                n.put("correct", s.correct());
                n.put("overReference", s.overReference(config.thresholdMillis()));
                sb.append(n.toString()).append('\n');
            }
            stageIndex++;
        }
        return sb.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks a companion file against the descriptor stored in the report. */
    public static boolean verifyCompanion(ObjectNode report, String jsonlText) {
        ObjectNode c = (ObjectNode) report.get("samplesCompanion");
        return c != null
                && c.get("sha256").asText().equals(sha256(jsonlText))
                && c.get("bytes").asLong() == jsonlText.getBytes(StandardCharsets.UTF_8).length
                && c.get("count").asLong() == jsonlText.lines().filter(l -> !l.isBlank()).count();
    }

    public ObjectNode toJson(String companionArtifactId) {
        requireSafeArtifactId(companionArtifactId);
        String jsonl = samplesJsonl();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("informationalOnly", true);
        root.put("scope", "steady-warm-capacity-discovery");
        root.put("disclaimer", "Observed local capacity for the disclosed hardware, commit, fixture, profile and generator boundary only; not an SLA, expected capacity, Oracle fit or production claim.");
        ObjectNode meta = root.putObject("identity");
        metadata.forEach((k, v) -> meta.put(k, v));
        ObjectNode cfg = root.putObject("config");
        cfg.put("thresholdMillis", config.thresholdMillis());
        cfg.put("thresholdSemantics", "strictly greater than threshold is over; exactly equal is not over");
        cfg.set("coarseLevels", MAPPER.valueToTree(config.coarseLevels()));
        cfg.put("maxUsers", config.maxUsers());
        cfg.put("maxMillisPerProfile", config.maxMillisPerProfile());
        cfg.put("observationWindowMillis", config.observationWindowMillis());
        cfg.put("drainTimeoutMillis", config.drainTimeoutMillis());
        cfg.put("confirmations", config.confirmations());
        cfg.put("probeBudgetBrowserContexts", config.probeBudget());
        cfg.put("seed", config.seed());
        cfg.put("craftBurstProfitRequests", config.craftBurstMin() + ".." + config.craftBurstMax() + " within one minute, then recipe detail, then the in-game pause");
        cfg.put("browserProbeSamplesPerJourneyPerStage", config.probeSamplesPerJourney());
        cfg.put("craftScope", "ALL (frontend default)");
        cfg.put("workloadPhases", "one continuous seeded timeline per user; each measured window is the next slice, so phases continue across stages");
        cfg.set("journeys", MAPPER.valueToTree(config.journeys()));
        ObjectNode prof = cfg.putObject("profile");
        prof.put("id", config.profile().id());
        prof.put("assumption", true);
        prof.put("browseThinkMillis", config.profile().browseMinMs() + ".." + config.profile().browseMaxMs());
        prof.put("inGamePauseMillis", config.profile().pauseMinMs() + ".." + config.profile().pauseMaxMs());
        prof.put("accountCraftingMixPercent", config.profile().accountPercent() + "/" + (100 - config.profile().accountPercent()));

        ObjectNode res = root.putObject("outcome");
        res.put("status", outcome.status().name());
        res.put("detail", outcome.detail());
        putNullable(res, "lastPassingLevel", outcome.lastPassingLevel());
        putNullable(res, "firstBreachLevel", outcome.firstBreachLevel());
        putNullable(res, "confirmedCapacityUsers", outcome.confirmedCapacity());
        res.put("cleanupRequired", outcome.cleanupRequired());

        ArrayNode stages = root.putArray("stages");
        for (StageObservation st : allStages()) {
            ObjectNode n = stages.addObject();
            n.put("role", st.role());
            n.put("level", st.level());
            n.put("seed", st.seed());
            n.put("elapsedMillis", st.elapsedMillis());
            n.put("scheduledStarts", st.scheduledStarts());
            n.put("achievedStarts", st.achievedStarts());
            n.put("lateStarts", st.lateStarts());
            n.put("missedStarts", st.missedStarts());
            n.put("generatorSaturated", st.generatorSaturated());
            StartAccounting a = st.accounting();
            ObjectNode acc = n.putObject("accounting");
            acc.set("journeys", MAPPER.valueToTree(a.journeys()));
            acc.set("polls", MAPPER.valueToTree(a.polls()));
            acc.put("httpCalls", a.httpCalls());
            acc.set("httpStatuses", MAPPER.valueToTree(a.httpStatuses()));
            acc.put("browserProbeCalls", a.browserProbeCalls());
            acc.put("browserApiCalls", a.browserApiCalls());
            acc.put("browserStatusPolls", a.browserPolls());
            acc.put("maxQueueDelayMillis", a.maxQueueDelayMillis());
            acc.put("note", "protocol users' starts are recorded inside workers; browser probes are sparse and counted inside L");
            if (st.invalidReason() != null) n.put("invalidReason", st.invalidReason());
            n.put("breached", st.breached(config.thresholdMillis()));
            n.put("browserBreached", st.breached(config.thresholdMillis()));
            n.put("protocolOverReference", st.protocolOver(config.thresholdMillis()));
            n.set("missingBrowserJourneys", MAPPER.valueToTree(st.missingJourneys(config.journeys())));
            n.put("browserSampleCount", st.browserSamples().size());
            n.put("sampleCount", st.samples().size());
            ObjectNode per = n.putObject("perJourney");
            Map<String, List<Long>> byJourney = new TreeMap<>();
            for (CapacitySample s : st.samples()) byJourney.computeIfAbsent(s.kind() + ":" + s.journey(), k -> new ArrayList<>()).add(s.durationMillis());
            byJourney.forEach((k, ds) -> {
                ObjectNode d = per.putObject(k);
                List<Double> vals = ds.stream().map(Long::doubleValue).toList();
                d.put("count", ds.size());
                d.put("min", ds.stream().mapToLong(Long::longValue).min().orElse(0));
                d.put("p50", Stats.percentile(vals, 50));
                d.put("p90", Stats.percentile(vals, 90));
                d.put("p95", Stats.percentile(vals, 95));
                d.put("p99", Stats.percentile(vals, 99));
                d.put("max", ds.stream().mapToLong(Long::longValue).max().orElse(0));
                d.put("overReferenceCount", ds.stream().filter(x -> x > config.thresholdMillis()).count());
                d.put("lowSampleWarning", ds.size() < 5);
            });
        }
        ArrayNode over = root.putArray("overReferenceSamples");
        int idx = 0;
        for (StageObservation st : allStages()) {
            for (CapacitySample s : st.samples()) {
                if (s.overReference(config.thresholdMillis())) {
                    ObjectNode o = over.addObject();
                    o.put("stageIndex", idx);
                    o.put("level", st.level());
                    o.put("role", st.role());
                    o.put("journey", s.journey());
                    o.put("kind", s.kind());
                    o.put("durationMillis", s.durationMillis());
                }
            }
            idx++;
        }
        ObjectNode comp = root.putObject("samplesCompanion");
        comp.put("schema", SAMPLE_SCHEMA);
        comp.put("artifactId", companionArtifactId);
        comp.put("count", jsonl.lines().filter(l -> !l.isBlank()).count());
        comp.put("bytes", jsonl.getBytes(StandardCharsets.UTF_8).length);
        comp.put("sha256", sha256(jsonl));
        return root;
    }

    public String toJsonString(String companionArtifactId) {
        try {
            return MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(toJson(companionArtifactId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Steady warm capacity discovery (informational)\n\n");
        sb.append("- Outcome: **").append(outcome.status()).append("** — ").append(outcome.detail()).append('\n');
        sb.append("- Reference: strictly greater than ").append(config.thresholdMillis()).append(" ms is over; exactly equal is not.\n");
        sb.append("- Profile (assumption, not measured): ").append(config.profile().id()).append(", seed ").append(config.seed()).append('\n');
        sb.append("- Last passing level: ").append(outcome.lastPassingLevel()).append("; first breach: ").append(outcome.firstBreachLevel())
                .append("; confirmed capacity: ").append(outcome.confirmedCapacity() == null ? "none established" : outcome.confirmedCapacity() + " users").append('\n');
        if (outcome.cleanupRequired()) sb.append("- **CLEANUP REQUIRED**: workers did not drain; isolated resources were retained.\n");
        metadata.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        sb.append("\n| Stage | Level | Scheduled | Achieved | Late | Missed | Browser samples | Missing journeys | Over ").append(config.thresholdMillis()).append(" ms |\n|---|---|---|---|---|---|---|---|---|\n");
        for (StageObservation st : allStages()) {
            long over = st.samples().stream().filter(s -> s.overReference(config.thresholdMillis())).count();
            sb.append("| ").append(st.role()).append(" | ").append(st.level()).append(" | ").append(st.scheduledStarts())
                    .append(" | ").append(st.achievedStarts()).append(" | ").append(st.lateStarts()).append(" | ").append(st.missedStarts())
                    .append(" | ").append(st.browserSamples().size()).append(" | ").append(st.missingJourneys(config.journeys()))
                    .append(" | ").append(over).append(" |\n");
        }
        sb.append("\nSparse browser probes are low-sample evidence. Capacity applies only to the disclosed hardware, fixture, profile and generator boundary.\n");
        return sb.toString();
    }

    private static void putNullable(ObjectNode n, String k, Integer v) {
        if (v == null) n.putNull(k); else n.put(k, v);
    }
}
