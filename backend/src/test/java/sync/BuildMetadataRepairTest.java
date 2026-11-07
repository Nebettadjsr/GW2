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

import web.dto.CharacterBuildTabsResponse;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable production-migration schema; no GW2 or item-metadata network access. */
class BuildMetadataRepairTest {
    private static final String A_ID = "10000000-0000-0000-0000-0000000000a1";
    private static final String B_ID = "10000000-0000-0000-0000-0000000000b2";
    private static final AccountScope A = AccountScope.of(A_ID);
    private static final AccountScope B = AccountScope.of(B_ID);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_build_meta_" + UUID.randomUUID().toString().replace("-", "");
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

    private static final String SK = "{\"heal\":7,\"utilities\":[2,null,4],\"elite\":5}";
    private static final String TABS = "[{\"tab\":1,\"is_active\":true,\"build\":{\"name\":\"T1\",\"profession\":\"Ranger\","
            + "\"specializations\":[{\"id\":7,\"traits\":[701,null,703]},{\"id\":null,\"traits\":[null,null,null]},{\"id\":9,\"traits\":[null,null,null]}],"
            + "\"skills\":" + SK + ",\"aquatic_skills\":" + SK
            + ",\"legends\":[\"Legend1\",null],\"aquatic_legends\":[null,null],"
            + "\"pets\":{\"terrestrial\":[7,null],\"aquatic\":[null,null]}}}]";
    private static final String ICON = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";

    /** Records every call; answers from fixed per-endpoint bodies keyed by endpoint. */
    private static final class FakeSource implements BuildMetadataRepair.Source {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> bodies = new HashMap<>();
        boolean fail;
        public JsonNode fetch(String endpoint, List<String> ids) throws Exception {
            calls.add(endpoint + "?" + String.join(",", ids));
            if (fail) throw new java.io.IOException("HTTP 503");
            return M.readTree(bodies.getOrDefault(endpoint, "[]"));
        }
    }

    private static FakeSource fullSource() {
        FakeSource s = new FakeSource();
        s.bodies.put("specializations", "[{\"id\":7,\"name\":\"Spec7\",\"profession\":\"Ranger\",\"elite\":false,\"icon\":\"" + ICON
                + "\",\"minor_traits\":[701,702,703],\"major_traits\":[711,712,713,721,722,723,731,732,733]},"
                + "{\"id\":9,\"name\":\"Spec9\",\"profession\":\"Ranger\",\"elite\":true,\"minor_traits\":[901],\"major_traits\":[911]}]");
        s.bodies.put("traits", "[{\"id\":701,\"name\":\"Tr701\",\"icon\":\"" + ICON + "\",\"specialization\":7,\"tier\":1,\"slot\":\"Minor\"},"
                + "{\"id\":703,\"name\":\"Tr703\",\"specialization\":7,\"tier\":3,\"slot\":\"Minor\"},"
                + "{\"id\":711,\"name\":\"Tr711\",\"specialization\":7,\"tier\":1,\"slot\":\"Major\"}]");
        s.bodies.put("skills", "[{\"id\":7,\"name\":\"Skill7\",\"icon\":\"" + ICON + "\"},{\"id\":2,\"name\":\"Skill2\"},"
                + "{\"id\":4,\"name\":\"Skill4\"},{\"id\":5,\"name\":\"Skill5\"},{\"id\":40,\"name\":\"Swap\"}]");
        s.bodies.put("pets", "[{\"id\":7,\"name\":\"Pet7\",\"icon\":\"" + ICON + "\"}]");
        s.bodies.put("legends", "[{\"id\":\"Legend1\",\"swap\":40}]");
        return s;
    }

    private void publishRoster(AccountScope scope, String... names) throws Exception {
        List<CharacterSync.CharPayload> payloads = new ArrayList<>();
        for (String n : names) {
            payloads.add(new CharacterSync.CharPayload(new CharacterInfo(n, "Ranger", "Norn", "Male", 80, null),
                    List.of(), List.of(), List.of(new model.CharacterItemRow("BAG", 0, 0, null, 100, 2, null, null))));
        }
        try (Connection c = connect()) { c.setSchema(schema); CharacterSync.publishRoster(c, scope, payloads, NOW); }
    }

    private void publishBuilds(AccountScope scope, String accountId) throws Exception {
        CharacterBuildTabsSync.sync(scope, url -> url.endsWith("/v2/account")
                ? M.readTree("{\"id\":\"" + accountId + "\"}") : M.readTree(TABS));
    }

    private long count(String sql) throws Exception {
        try (var st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private CharacterBuildTabsService.Result read(AccountScope scope, String accountId, String name) throws Exception {
        var service = new CharacterBuildTabsService(null, new CharacterBuildTabsRepository(), () -> {
            try { Connection c = connect(); c.setSchema(schema); return c; }
            catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); }
        }, Clock.systemUTC(), Duration.ofHours(1));
        var ctx = new AccountContext(accountId, EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.INVENTORIES, AccountGrant.BUILDS), Set.of(), "ctx", Instant.now().plusSeconds(60));
        return service.read(ctx, scope, name);
    }

    private CharacterBuildTabsResponse body(CharacterBuildTabsService.Result r) {
        return CharacterBuildTabsResponse.respond(r).getBody();
    }

    @Test
    void coldBatchesPerNamespaceWarmFetchesNothingAndOverlappingIdsStayDistinct() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        FakeSource source = fullSource();
        var repair = new BuildMetadataRepair(source, NOW);
        repair.repairStored(A);

        // One request per namespace, deduplicated; trait request is the selected plus the spec closure.
        assertEquals(List.of("specializations?7,9", "traits?701,702,703,711,712,713,721,722,723,731,732,733,901,911",
                "legends?Legend1", "skills?2,4,5,7,40", "pets?7"), source.calls);
        source.calls.clear();
        repair.repairStored(A);
        assertEquals(List.of("traits?702,712,713,721,722,723,731,732,733,901,911"), source.calls,
                "only the choices upstream did not return are asked again; nothing already stored is");

        var t = body(read(A, A_ID, "Ana")).tabs().get(0);
        var s7 = t.specializations().get(0);
        assertTrue(s7.specialization().resolved());
        assertEquals("Spec7", s7.specialization().name());
        assertEquals("/api/resources/specializations/7/icon/" + infra.icons.IconSourcePolicy.accept(ICON).orElseThrow().sourceKey() + ".png",
                s7.specialization().iconUrl());
        assertEquals(3, s7.minorTraits().size());
        assertEquals(9, s7.majorTraits().size());
        assertEquals(List.of(701, 702, 703), s7.minorTraits().stream().map(x -> x.id()).toList(), "upstream order retained");
        assertFalse(s7.minorTraits().get(1).resolved(), "choice upstream did not describe stays explicit");
        assertTrue(s7.majorTraits().get(0).resolved());
        assertEquals(1, s7.majorTraits().get(0).tier());
        assertEquals("Major", s7.majorTraits().get(0).slot());
        assertEquals(701, s7.traits().get(0).id());
        assertNull(s7.traits().get(1));
        assertNull(t.specializations().get(1).specialization());
        assertNull(t.specializations().get(1).minorTraits());
        assertEquals("Skill7", t.skills().heal().name(), "skill 7 is not trait 7 or specialization 7");
        assertEquals("Pet7", t.pets().get(0).name());
        assertEquals("Skill2", t.skills().utilities().get(0).name());
        assertNull(t.skills().utilities().get(1));
        assertNull(t.skills().utilities().get(0).iconUrl(), "no upstream icon is not fabricated");
        var legend = t.legends().get(0);
        assertTrue(legend.resolved());
        assertEquals(40, legend.swapSkill().id());
        assertEquals("Swap", legend.swapSkill().name());
        assertNull(t.legends().get(1));
        assertEquals(0, count("SELECT count(*) FROM build_specializations WHERE specialization_id NOT IN (7, 9)"));
    }

    @Test
    void failureKeepsValidBuildsPublishedAndLastGoodMetadataAndRecoversLater() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        long gen = generation(A);
        FakeSource down = fullSource();
        down.fail = true;
        new BuildMetadataRepair(down, NOW).repairStored(A);
        assertEquals(0, count("SELECT count(*) FROM build_skills"));
        var r = read(A, A_ID, "Ana");
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, r.state());
        var t = body(r).tabs().get(0);
        assertFalse(t.skills().heal().resolved());
        assertEquals(7, t.skills().heal().id());
        assertNull(t.specializations().get(0).minorTraits(), "unresolved specialization offers no invented choices");

        new BuildMetadataRepair(fullSource(), NOW).repairStored(A);
        assertTrue(body(read(A, A_ID, "Ana")).tabs().get(0).skills().heal().resolved());
        assertEquals(gen, generation(A), "metadata repair never rewrites the account build publication");

        // A later failing/malformed answer must not damage the good rows.
        FakeSource bad = new FakeSource();
        bad.bodies.put("skills", "[{\"id\":7,\"name\":\"\"},{\"id\":\"x\"},{\"id\":999,\"name\":\"unrequested\"}]");
        new BuildMetadataRepair(bad, NOW).repair(new repo.BuildMetadataRepository.Refs(new TreeSet<>(), new TreeSet<>(),
                new TreeSet<>(Set.of(7, 8)), new TreeSet<>(), new TreeSet<>()));
        assertEquals("Skill7", body(read(A, A_ID, "Ana")).tabs().get(0).skills().heal().name());
        assertEquals(0, count("SELECT count(*) FROM build_skills WHERE skill_id = 999"));
    }

    @Test
    void sharedMetadataServesTwoAccountsWithOneFetchAndIsolatedBuildReads() throws Exception {
        publishRoster(A, "Ana");
        publishRoster(B, "Bo");
        publishBuilds(A, A_ID);
        publishBuilds(B, B_ID);
        FakeSource source = fullSource();
        new BuildMetadataRepair(source, NOW).repairStored(A);
        int afterA = source.calls.size();
        new BuildMetadataRepair(source, NOW).repairStored(B);
        assertEquals(List.of("traits?702,712,713,721,722,723,731,732,733,901,911"),
                source.calls.subList(afterA, source.calls.size()), "B re-asks only unanswered closure ids");
        assertEquals("Spec7", body(read(B, B_ID, "Bo")).tabs().get(0).specializations().get(0).specialization().name());
        assertEquals(CharacterBuildTabsService.State.NOT_FOUND, read(B, B_ID, "Ana").state());
        assertEquals(1, count("SELECT count(*) FROM build_specializations WHERE specialization_id = 7"));
    }

    @Test
    void interruptionPropagatesAndLeavesFlagSet() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        var source = new BuildMetadataRepair.Source() {
            public JsonNode fetch(String e, List<String> ids) throws Exception { throw new InterruptedException(); }
        };
        assertThrows(InterruptedException.class, () -> new BuildMetadataRepair(source, NOW).repairStored(A));
        assertTrue(Thread.interrupted());
    }

    private long generation(AccountScope s) throws Exception {
        try (Connection c = connect()) {
            c.setSchema(schema);
            return new repo.AccountSourceStateRepository().read(c, s, "CHARACTER_BUILD_TABS").map(x -> x.generation()).orElse(0L);
        }
    }
}
