package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import repo.CraftingGraphCache;
import repo.Db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STORY-KPI-002 steady warm capacity discovery against the real Spring/Flyway/PostgreSQL application on
 * loopback, the unchanged built frontend behind a same-origin proxy and sequential real-Chromium probes.
 * Explicit, never part of default CI (name ends in IT; run with {@code -Dtest=CapacityExperimentIT}).
 *
 * <ul>
 *   <li>{@code -Dgw2.capacity.mode=boundary} (default): tiny correctness/lifecycle check, 2 users, no capacity claim.</li>
 *   <li>{@code -Dgw2.capacity.mode=experiment}: baseline, warmup, coarse, refine, confirm; writes JSON/Markdown/JSONL
 *       under {@code target/capacity/}. Optional: {@code gw2.capacity.profile}, {@code .seed}, {@code .levels},
 *       {@code .max-users}, {@code .window-ms}, {@code .budget-ms}, {@code .confirmations}, {@code .probes}.</li>
 * </ul>
 * The private snapshot is used only when {@link SnapshotImporter#ENV} is set; otherwise the synthetic fixture.
 * No timing assertion is made: a slow run is a result, an incorrect or unisolated one is a failure.
 */
@SpringBootTest(classes = web.Gw2ApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000", "gw2.owner.account-guid=" + SyntheticFixtures.A})
@Import(PopulatedFixtureHttpTest.Proofs.class)
class CapacityExperimentIT {
    static final String SCHEMA = "kpi_" + UUID.randomUUID().toString().replace("-", "");
    static final ObjectMapper JSON = new ObjectMapper();
    static String mode() { return System.getProperty("gw2.capacity.mode", "boundary"); }
    static final Map<String, String> IDENTITY = new LinkedHashMap<>();
    static int copyCount;
    static Path graphFile;

    @LocalServerPort int port;

    static String prop(String k, String d) { return System.getProperty("gw2.capacity." + k, d); }

    static CapacityConfig config() {
        boolean boundary = mode().equals("boundary");
        WorkloadProfile profile = WorkloadProfile.parse(prop("profile", "balanced"));
        long seed = Long.parseLong(prop("seed", "42"));
        CapacityConfig d = CapacityConfig.defaults(profile, seed);
        List<Integer> levels = boundary ? List.of(1, 2) : Arrays.stream(prop("levels", "1,2,4,8,16,32,64").split(",")).map(String::trim).map(Integer::parseInt).toList();
        int maxUsers = boundary ? 2 : Integer.parseInt(prop("max-users", "64"));
        long window = Long.parseLong(prop("window-ms", boundary ? "6000" : String.valueOf(d.observationWindowMillis())));
        long budget = Long.parseLong(prop("budget-ms", boundary ? "300000" : String.valueOf(d.maxMillisPerProfile())));
        int confirmations = Integer.parseInt(prop("confirmations", boundary ? "1" : "3"));
        int probes = Integer.parseInt(prop("probes", "1"));
        return new CapacityConfig(d.thresholdMillis(), levels, maxUsers, budget, confirmations, probes, seed, profile,
                d.journeys(), window, d.drainTimeoutMillis());
    }

    @BeforeAll
    static void load() throws Exception {
        CapacityConfig cfg = config();
        copyCount = cfg.maxUsers();
        FixtureSnapshot original;
        SnapshotImporter.Result priv = SnapshotImporter.fromConfiguration();
        if (priv.available()) {
            original = priv.snapshot();
            IDENTITY.put("fixtureSource", "private-local-snapshot");
        } else {
            FixtureSnapshot base = SyntheticFixtures.build();
            Map<String, List<ObjectNode>> only = new LinkedHashMap<>();
            for (String t : FixtureSnapshot.ALL_TABLES) {
                boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
                only.put(t, base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList());
            }
            original = new FixtureSnapshot(base.kind(), base.version(), only, Map.of(), base.sourceRevision(), null);
            IDENTITY.put("fixtureSource", "synthetic");
        }
        // Warm-state preparation, outside every measured window and disclosed: only timestamps are shifted so
        // the captured cache is current; facts and parent/optional-source relationships are preserved.
        FixtureSnapshot prepared = SnapshotImporter.shiftClock(original, OffsetDateTime.now());
        SnapshotImporter.Copies copies = SnapshotImporter.principalCopies(prepared, copyCount);
        PopulatedFixtureHttpTest.copies = copies;
        IDENTITY.put("originalInputIdentity", original.identity());
        IDENTITY.put("preparedIdentity", prepared.identity());
        IDENTITY.put("loadedFixtureIdentity", copies.snapshot().identity());
        IDENTITY.put("warmPolicy", "timestamps shifted to now by a single delta; facts, relationships and production freshness checks unchanged; not evidence the raw capture was fresh");
        IDENTITY.put("principalCopies", String.valueOf(copyCount));
        IDENTITY.put("sharedCatalog", "loaded once for all principals");
        PopulatedFixtureIsolationIT.createSchema(SCHEMA);
        try (Connection c = PopulatedFixtureIsolationIT.connect()) { FixtureLoader.load(c, SCHEMA, copies.snapshot()); }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
        graphFile = Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json");
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, graphFile.toString());
    }

    @AfterAll
    static void cleanup() throws Exception {
        PopulatedFixtureHttpTest.drain(); // owned workers exit before the owned schema is dropped
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        if (graphFile != null) Files.deleteIfExists(graphFile);
        PopulatedFixtureIsolationIT.drop(SCHEMA);
    }

    private static String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return s;
        } catch (Exception e) { return "unavailable"; }
    }

    @Test
    void steadyWarmCapacityDiscovery() throws Exception {
        CapacityConfig cfg = config();
        Path frontend = Path.of(System.getProperty("gw2.capacity.frontend-dir", "../frontend")).toAbsolutePath().normalize();
        String backend = "http://127.0.0.1:" + port;
        CapacityOutcome outcome;
        BrowserProbe probe = new BrowserProbe(frontend, backend);
        CapacityStageRunner runner = new CapacityStageRunner(cfg, backend, PopulatedFixtureHttpTest::copyKey, probe,
                () -> PopulatedFixtureHttpTest.OUTSTANDING.get() == 0);
        try {
            outcome = new CapacityCoordinator(cfg, runner).discover();
        } finally {
            runner.shutdown();
            probe.close();
        }
        assertFalse(probe.isAlive(), "browser probe process must be stopped");
        assertEquals(List.of(), PopulatedFixtureHttpTest.UNEXPECTED, "no external/live edge may be reached");

        Map<String, String> meta = new LinkedHashMap<>(IDENTITY);
        meta.put("commit", git("rev-parse", "HEAD"));
        meta.put("dirty", git("status", "--porcelain").isEmpty() ? "clean" : "dirty");
        meta.put("mode", mode());
        meta.put("logicalCores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        meta.put("java", System.getProperty("java.version"));
        meta.put("generatorBoundary", "protocol generator and one sequential Chromium probe run in the same host as the application and PostgreSQL (colocated; logical cores are not Oracle OCPU)");
        meta.put("userAccounting", "level L = " + cfg.probeBudget() + " browser-probe principal(s) + (L - probes) protocol users; probes are inside L");
        meta.put("resourceObservations", "unavailable: no container/process resource probe is attached to this local run");
        meta.put("noLiveUpstream", "all proof, account, global and price edges are controlled fakes; unexpected calls fail the run");

        CapacityReport report = new CapacityReport(cfg, outcome, meta);
        String artifactId = "capacity-" + cfg.profile().id() + "-" + cfg.seed();
        String jsonl = report.samplesJsonl();
        String json = report.toJsonString(artifactId);
        assertTrue(CapacityReport.verifyCompanion((ObjectNode) JSON.readTree(json), jsonl), "companion round trip");
        Path out = Path.of("target", "capacity");
        Files.createDirectories(out);
        Files.writeString(out.resolve(artifactId + ".json"), json);
        Files.writeString(out.resolve(artifactId + ".jsonl"), jsonl);
        Files.writeString(out.resolve(artifactId + ".md"), report.toMarkdown());
        System.out.println("capacity outcome: " + outcome.status() + " - " + outcome.detail());

        assertFalse(outcome.cleanupRequired(), "workers must drain");
        if (mode().equals("boundary")) {
            // Correctness boundary only: no timing assertion.
            assertNotEquals(CapacityOutcome.Status.INVALID, outcome.status(), outcome.detail());
            StageObservation baseline = outcome.baseline().get(0);
            assertTrue(baseline.coverageComplete(cfg.journeys()), "every enabled journey has a real-browser baseline sample");
            assertTrue(baseline.samples().stream().allMatch(s -> s.finalUsable() && s.correct()), "baseline samples are final and correct");
            assertTrue(outcome.measured().stream().flatMap(m -> m.samples().stream()).allMatch(s -> s.correct()), "isolation/consistency holds under load");
        }
    }
}
