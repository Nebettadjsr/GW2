package perf;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import repo.AccountOverviewRepository;
import repo.AccountSyncStateRepository;
import repo.BankRepository;
import repo.CharacterRepository;
import repo.Db;
import repo.EnvConfig;
import model.AccountScope;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Flyway + production repositories on a disposable schema: the populated synthetic fixtures (and,
 * when the maintainer opts in, the local snapshot) load through the shared loader and the independent
 * principals stay partitioned for bank, characters and saved Status counts. Never touches public.
 * Run: {@code mvn -f backend/pom.xml -Dtest=PopulatedFixtureIsolationIT test} (Surefire; the capture revision is recorded as provenance.sourceRevision, the report commit is the executing code) (DATABASE_URL/USER/PASSWORD set).
 */
class PopulatedFixtureIsolationIT {

    static final String SCHEMA = "kpi_" + UUID.randomUUID().toString().replace("-", "");

    static Connection connect() throws Exception {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }

    static void createSchema(String schema) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) { st.execute("CREATE SCHEMA " + schema); }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
    }

    static void drop(String schema) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    @BeforeAll
    static void load() throws Exception {
        createSchema(SCHEMA);
        try (Connection c = connect()) { FixtureLoader.load(c, SCHEMA, SyntheticFixtures.build()); }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        drop(SCHEMA);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expected(String account) { return (Map<String, Object>) SyntheticFixtures.expected().get(account); }

    @Test
    void bankAndCharacterReadsArePartitionedPerPrincipalWithOverlappingIds() throws Exception {
        BankRepository bank = new BankRepository();
        CharacterRepository characters = new CharacterRepository();
        try (Connection c = Db.open()) {
            for (String account : SyntheticFixtures.ACCOUNTS) {
                AccountScope scope = AccountScope.of(account);
                var rows = bank.loadBankSlots(c, scope);
                assertEquals(expected(account).get("bankSlots"), rows.size(), account);
                assertEquals(((Number) expected(account).get("bankItemCountSum")).longValue(),
                        rows.stream().mapToLong(r -> r.count() == null ? 0 : r.count()).sum(), account);
                assertEquals(expected(account).get("characters"), characters.loadRoster(c, scope).size(), account);
            }
            var aNames = characters.loadRoster(c, AccountScope.of(SyntheticFixtures.A)).stream().map(CharacterRepository.CharacterProfile::name).toList();
            var bNames = characters.loadRoster(c, AccountScope.of(SyntheticFixtures.B)).stream().map(CharacterRepository.CharacterProfile::name).toList();
            assertTrue(aNames.contains("Aria") && !aNames.contains("Cato"));
            assertTrue(bNames.contains("Cato") && !bNames.contains("Aria"));
        }
    }

    @Test
    void savedStatusCountsStayPartitionedAndStaleOrFailedSourcesStayHonest() throws Exception {
        Set<String> sources = Set.of(AccountSyncStateRepository.CHARACTERS, AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.MATERIALS);
        try (Connection c = Db.open()) {
            AccountOverviewRepository overview = new AccountOverviewRepository();
            for (String account : SyntheticFixtures.ACCOUNTS) {
                Map<String, AccountOverviewRepository.Counts> counts = overview.read(c, AccountScope.of(account), sources);
                @SuppressWarnings("unchecked")
                List<String> expectedSources = (List<String>) expected(account).get("overviewSources");
                assertEquals(Set.copyOf(expectedSources), counts.keySet(), account);
                var m = counts.get(AccountSyncStateRepository.MATERIALS);
                if (m != null) {
                    assertEquals(((Number) expected(account).get("materialRows")).longValue(), m.count(), account);
                    assertEquals(((Number) expected(account).get("materialTotal")).longValue(), m.total(), account);
                }
                var ch = counts.get(AccountSyncStateRepository.CHARACTERS);
                if (ch != null) assertEquals(((Number) expected(account).get("characters")).longValue(), ch.count(), account);
                var rc = counts.get(AccountSyncStateRepository.RECIPES);
                if (rc != null) assertEquals(((Number) expected(account).get("accountRecipes")).longValue(), rc.count(), account);
            }
            var stale = overview.read(c, AccountScope.of(SyntheticFixtures.D), sources).get(AccountSyncStateRepository.MATERIALS);
            assertTrue(stale.state().completedAt().isBefore(java.time.Instant.parse("2021-01-01T00:00:00Z")), "stale marker is preserved, not refreshed");
        }
    }

    @Test
    void loaderRefusesPublicAndNonDisposableSchemas() throws Exception {
        try (Connection c = connect()) {
            assertThrows(FixtureLoader.LoadException.class, () -> FixtureLoader.load(c, "public", SyntheticFixtures.build()));
            assertThrows(FixtureLoader.LoadException.class, () -> FixtureLoader.load(c, SCHEMA, SyntheticFixtures.build()), "not newly created");
        }
    }

    @Test
    void syntheticStandInSnapshotImportsRemapsAndLoadsThroughTheSameLoader() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("kpi-standin");
        String schema = "kpi_" + UUID.randomUUID().toString().replace("-", "");
        try {
            SnapshotTestSupport.write(dir, java.util.function.UnaryOperator.identity());
            FixtureSnapshot imported = SnapshotImporter.read(dir);
            createSchema(schema);
            try (Connection c = connect()) { FixtureLoader.load(c, schema, imported); }
            try (Connection c = connect(); Statement st = c.createStatement()) {
                st.execute("SET search_path TO " + schema);
                try (var rs = st.executeQuery("SELECT count(*) FROM characters WHERE name LIKE 'Fake Character %'")) {
                    rs.next();
                    assertEquals(imported.rows("characters").size(), rs.getInt(1));
                }
                try (var rs = st.executeQuery("SELECT count(*) FROM account_registry WHERE account_id::text LIKE 'deadbeef%'")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "no real principal reaches the database");
                }
            }
        } finally {
            drop(schema);
            try (var files = java.nio.file.Files.list(dir)) { for (var f : (Iterable<java.nio.file.Path>) files::iterator) java.nio.file.Files.delete(f); }
            java.nio.file.Files.delete(dir);
        }
    }

    @Test
    void optionalLocalSnapshotImportsIntoAFreshSchemaAndWritesSafeReadiness() throws Exception {
        SnapshotImporter.Result r = SnapshotImporter.fromConfiguration();
        Assumptions.assumeTrue(r.available(), "no local snapshot opted in: " + r.unavailableReason());
        String schema = "kpi_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        try (Connection c = connect()) {
            FixtureLoader.load(c, schema, r.snapshot());
        } finally {
            drop(schema);
        }
        java.nio.file.Path out = java.nio.file.Path.of("target", "perf");
        java.nio.file.Files.createDirectories(out);
        MeasurementReport report = new MeasurementReport(ApplicationBenchmarkHarness.commit(), java.time.Instant.now(), r.snapshot().version(), Map.of());
        report.setFixtureProvenance(FixtureProvenance.of(r.snapshot()));
        java.nio.file.Files.writeString(out.resolve("snapshot-readiness.json"), report.toJsonString());
        java.nio.file.Files.writeString(out.resolve("snapshot-readiness.md"), report.toMarkdown());
    }
}
