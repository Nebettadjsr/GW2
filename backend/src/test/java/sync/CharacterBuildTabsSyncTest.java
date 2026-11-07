package sync;

import application.CharacterBuildTabsService;
import application.access.AccountContext;
import application.access.AccountGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import model.CharacterInfo;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import repo.CharacterBuildTabsRepository;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable production-migration schema; no GW2 or item-metadata network access. */
class CharacterBuildTabsSyncTest {
    private static final String A_ID = "10000000-0000-0000-0000-0000000000a1";
    private static final String B_ID = "10000000-0000-0000-0000-0000000000b2";
    private static final AccountScope A = AccountScope.of(A_ID);
    private static final AccountScope B = AccountScope.of(B_ID);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_build_tabs_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement s = admin.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        admin.setSchema(schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD")).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Statement s = admin.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        admin.close();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }

    private void publishRoster(AccountScope scope, String... names) throws Exception {
        List<CharacterSync.CharPayload> payloads = new ArrayList<>();
        for (String n : names) {
            payloads.add(new CharacterSync.CharPayload(new CharacterInfo(n, "Engineer", "Norn", "Male", 80, null),
                    List.of(), List.of(), List.of(new model.CharacterItemRow("BAG", 0, 0, null, 100, 2, null, null))));
        }
        try (Connection c = connect()) {
            c.setSchema(schema);
            CharacterSync.publishRoster(c, scope, payloads, NOW);
        }
    }

    private static final String SK = "{\"heal\":1,\"utilities\":[2,null,4],\"elite\":5}";
    private static String tab(int n, boolean active, String extra) {
        return "{\"tab\":" + n + ",\"is_active\":" + active + ",\"build\":{\"name\":\"T" + n + "\",\"profession\":\"Ranger\","
                + "\"specializations\":[{\"id\":7,\"traits\":[1,null,3]},null,{\"id\":9,\"traits\":[null,null,null]}],"
                + "\"skills\":" + SK + ",\"aquatic_skills\":" + SK + extra + "}}";
    }
    private static final String TABS = "[" + tab(2, false, "") + "," + tab(1, true,
            ",\"pets\":{\"terrestrial\":[11,null],\"aquatic\":[null,null]}") + "]";

    private AccountSync.AuthFetcher fetcher(String accountId, List<String> urls, Map<String, String> bodies) {
        return url -> {
            urls.add(url);
            if (url.endsWith("/v2/account")) return M.readTree("{\"id\":\"" + accountId + "\"}");
            for (var e : bodies.entrySet()) if (url.contains(e.getKey())) return M.readTree(e.getValue());
            throw new IllegalStateException("unexpected " + url);
        };
    }

    private long count(String sql, Object... args) throws Exception {
        try (var ps = admin.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private long generation(AccountScope s, String source) throws Exception {
        try (Connection c = connect()) {
            c.setSchema(schema);
            return new repo.AccountSourceStateRepository().read(c, s, source).map(x -> x.generation()).orElse(0L);
        }
    }

    private CharacterBuildTabsService service(Duration maxAge) {
        return new CharacterBuildTabsService(null, new CharacterBuildTabsRepository(), () -> {
            try {
                Connection c = connect();
                c.setSchema(schema);
                return c;
            } catch (java.sql.SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new java.sql.SQLException(e);
            }
        }, Clock.systemUTC(), maxAge);
    }

    private static AccountContext ctx(String id, AccountGrant... grants) {
        return new AccountContext(id, EnumSet.copyOf(List.of(grants)), Set.of(), "ctx", Instant.now().plusSeconds(60));
    }

    private static final AccountGrant[] ALL = {AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
            AccountGrant.INVENTORIES, AccountGrant.BUILDS};

    @Test
    void requestUsesEncodedNameTabsAllAndPinnedSchema() {
        assertEquals("https://api.guildwars2.com/v2/characters/Scribe%20Ana%2F%C3%A9/buildtabs"
                + "?tabs=all&v=2025-08-29T01:00:00.000Z", CharacterBuildTabsSync.url("Scribe Ana/é"));
    }

    @Test
    void missingParentRejectsBeforeAnyFetch() throws Exception {
        List<String> urls = new ArrayList<>();
        assertThrows(CharacterBuildTabsSync.PrerequisiteUnavailableException.class,
                () -> CharacterBuildTabsSync.sync(A, fetcher(A_ID, urls, Map.of())));
        assertTrue(urls.stream().noneMatch(u -> u.contains("buildtabs")));
        assertEquals(0, count("SELECT count(*) FROM account_source_state"));
    }

    @Test
    void publishesOrderedTabsAndReadsThemBackWithoutTouchingCharacters() throws Exception {
        publishRoster(A, "Ana", "Bo");
        long parentGen = generation(A, "CHARACTERS");
        long itemsBefore = count("SELECT coalesce(sum(count),0) FROM character_items");
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("/characters/Ana/buildtabs", TABS);
        bodies.put("/characters/Bo/buildtabs", "[]");
        List<String> urls = new ArrayList<>();
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, urls, bodies));
        assertTrue(urls.contains("https://api.guildwars2.com/v2/characters/Ana/buildtabs?tabs=all&v=2025-08-29T01:00:00.000Z"));
        assertEquals(parentGen, generation(A, "CHARACTERS"));
        assertEquals(1, generation(A, "CHARACTER_BUILD_TABS"));
        assertEquals(itemsBefore, count("SELECT coalesce(sum(count),0) FROM character_items"));

        var r = service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, r.state());
        assertEquals(parentGen, r.coveredParentGeneration());
        assertEquals(List.of(1, 2), r.tabs().stream().map(t -> t.number()).toList());
        var t1 = r.tabs().get(0);
        assertTrue(t1.active());
        assertEquals("Ranger", t1.profession());
        assertEquals(3, t1.specializations().size());
        assertNull(t1.specializations().get(1).id());
        assertEquals(Arrays.asList(1, null, 3), t1.specializations().get(0).traits());
        assertEquals(Arrays.asList(2, null, 4), t1.skills().utilities());
        assertEquals(Arrays.asList(11, null), t1.pets());
        assertEquals(Arrays.asList(null, null), t1.aquaticPets());
        assertNull(t1.legends());
        assertNull(r.tabs().get(1).pets());
        assertEquals(List.of(), service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Bo").tabs(), "valid empty tabs");
    }

    @Test
    void emptyRosterPublishesSuccessfully() throws Exception {
        publishRoster(A);
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of()));
        assertEquals(1, generation(A, "CHARACTER_BUILD_TABS"));
        assertEquals(0, count("SELECT count(*) FROM character_build_tabs"));
    }

    @Test
    void malformedResponseKeepsLastGoodPublication() throws Exception {
        publishRoster(A, "Ana");
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", TABS)));
        long tabs = count("SELECT count(*) FROM character_build_tabs");
        assertThrows(IllegalStateException.class, () -> CharacterBuildTabsSync.sync(A,
                fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[" + tab(1, false, "") + "]"))));
        assertEquals(tabs, count("SELECT count(*) FROM character_build_tabs"));
        assertEquals(1, generation(A, "CHARACTER_BUILD_TABS"));
    }

    @Test
    void sqlFailureAfterValidWritesRollsBackRowsMarkerAndCoverageTogether() throws Exception {
        publishRoster(A, "Ana");
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", TABS)));
        long tabs = count("SELECT count(*) FROM character_build_tabs");
        long specs = count("SELECT count(*) FROM character_build_tab_specializations");
        try (Statement s = admin.createStatement()) {
            // rows insert fine, the coverage upsert (last write) then fails
            s.execute("ALTER TABLE character_build_coverage ADD CONSTRAINT boom CHECK (parent_generation < 0) NOT VALID");
        }
        assertThrows(Exception.class, () -> CharacterBuildTabsSync.sync(A,
                fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[" + tab(1, true, "") + "]"))));
        assertEquals(tabs, count("SELECT count(*) FROM character_build_tabs"));
        assertEquals(specs, count("SELECT count(*) FROM character_build_tab_specializations"));
        assertEquals(1, generation(A, "CHARACTER_BUILD_TABS"));
    }

    @Test
    void parentGenerationChangeDuringFetchPublishesNothing() throws Exception {
        publishRoster(A, "Ana");
        var capture = new CharacterBuildTabsSync.Capture(generation(A, "CHARACTERS"), List.of("Ana"));
        publishRoster(A, "Ana");
        try (Connection c = connect()) {
            c.setSchema(schema);
            assertThrows(CharacterBuildTabsSync.ParentChangedException.class,
                    () -> CharacterBuildTabsSync.publish(c, A, capture, Map.of("Ana", List.of()), NOW));
        }
        assertEquals(0, generation(A, "CHARACTER_BUILD_TABS"));
        assertEquals(0, count("SELECT count(*) FROM character_build_coverage"));
    }

    @Test
    void readDistinguishesMissingIncompatibleStaleGrantsAndNotFound() throws Exception {
        var svc = service(Duration.ofHours(1));
        assertEquals("PARENT_NOT_PUBLISHED", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        publishRoster(A, "Ana");
        assertEquals("COVERAGE_NOT_PUBLISHED", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        assertEquals(CharacterBuildTabsService.State.NOT_FOUND, svc.read(ctx(A_ID, ALL), A, "Nobody").state());
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[]")));
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, svc.read(ctx(A_ID, ALL), A, "Ana").state());
        assertEquals("BUILD_TABS_SOURCE_EXPIRED", service(Duration.ofSeconds(-1)).read(ctx(A_ID, ALL), A, "Ana").reason());
        assertEquals("BUILD_TABS_GRANT_REQUIRED", svc.read(ctx(A_ID, AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.INVENTORIES), A, "Ana").reason());
        assertEquals("PARENT_GRANT_REQUIRED", svc.read(ctx(A_ID, AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.BUILDS), A, "Ana").reason());
        publishRoster(A, "Ana");
        var r = svc.read(ctx(A_ID, ALL), A, "Ana");
        assertEquals("COVERAGE_INCOMPATIBLE", r.reason());
        assertNull(r.tabs(), "absent/incompatible tabs never read as []");
    }

    @Test
    void firstFailureIsUnavailableUnlessRetryingAndLastGoodSurvivesLaterFailure() throws Exception {
        publishRoster(A, "Ana");
        boolean[] flags = new boolean[2];
        var status = new application.CharacterRosterService.RefreshStatus() {
            public boolean isRefreshing(AccountScope s, String src) { return flags[0]; }
            public boolean lastRefreshFailed(AccountScope s, String src) { return flags[1]; }
        };
        var svc = new CharacterBuildTabsService(status, new CharacterBuildTabsRepository(), () -> {
            try { Connection c = connect(); c.setSchema(schema); return c; }
            catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); }
        }, Clock.systemUTC(), Duration.ofHours(1));
        flags[1] = true;
        var failed = svc.read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(CharacterBuildTabsService.State.UNAVAILABLE, failed.state());
        assertNull(failed.tabs());
        flags[0] = true;
        assertEquals("REFRESH_IN_PROGRESS", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        flags[0] = false;
        flags[1] = false;
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", TABS)));
        flags[1] = true;
        var stale = svc.read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(CharacterBuildTabsService.State.STALE, stale.state());
        assertEquals(2, stale.tabs().size());
    }

    @Test
    void accountsWithTheSameCharacterNameStayIsolated() throws Exception {
        publishRoster(A, "Same");
        publishRoster(B, "Same");
        CharacterBuildTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Same/", TABS)));
        CharacterBuildTabsSync.sync(B, fetcher(B_ID, new ArrayList<>(), Map.of("/characters/Same/", "[]")));
        assertEquals(2, count("SELECT count(*) FROM character_build_tabs WHERE account_id = ?::uuid", A_ID));
        assertEquals(0, count("SELECT count(*) FROM character_build_tabs WHERE account_id = ?::uuid", B_ID));
        assertEquals(2, service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Same").tabs().size());
        assertEquals(0, service(Duration.ofHours(1)).read(ctx(B_ID, ALL), B, "Same").tabs().size());
    }
}
