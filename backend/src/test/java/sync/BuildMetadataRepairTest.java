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
import repo.BuildMetadataRepository;
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
        s.bodies.put("traits", "[{\"id\":701,\"name\":\"Tr701\",\"icon\":\"" + ICON + "\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Minor\"},"
                + "{\"id\":703,\"name\":\"Tr703\",\"specialization\":7,\"tier\":3,\"order\":0,\"slot\":\"Minor\"},"
                + "{\"id\":711,\"name\":\"Tr711\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Major\"}]");
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
    void repairsMissingDisplayDetailsWithoutHidingPreviouslyResolvedReferencesOrChangingCoverage() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        FakeSource source = fullSource();
        var specs = (com.fasterxml.jackson.databind.node.ArrayNode) M.readTree(source.bodies.get("specializations"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) specs.get(0)).put("background", ICON);
        source.bodies.put("specializations", specs.toString());
        var traits = (com.fasterxml.jackson.databind.node.ArrayNode) M.readTree(source.bodies.get("traits"));
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) traits.get(0);
        first.put("description", "<c=@reminder>Strike damage</c> is increased.");
        first.set("facts", M.readTree("[{\"type\":\"Percent\",\"text\":\"Damage Increase\",\"percent\":3,\"icon\":\"" + ICON + "\"}]"));
        source.bodies.put("traits", traits.toString());
        var repair = new BuildMetadataRepair(source, NOW);
        repair.repairStored(A);
        var before = body(read(A, A_ID, "Ana"));
        exec("UPDATE build_traits SET display_details = NULL WHERE trait_id = 701");
        assertTrue(body(read(A, A_ID, "Ana")).tabs().get(0).specializations().get(0).minorTraits().get(0).resolved());
        source.calls.clear();
        repair.repairStored(A);
        assertTrue(source.calls.stream().anyMatch(s -> s.startsWith("traits?701,")));
        var after = body(read(A, A_ID, "Ana"));
        assertEquals(before.builds(), after.builds());
        assertEquals(before.coveredParentGeneration(), after.coveredParentGeneration());
        var spec = after.tabs().get(0).specializations().get(0);
        assertTrue(spec.specialization().backgroundUrl().startsWith("/api/resources/specializations/7/background/"));
        assertEquals("Strike damage is increased.", spec.minorTraits().get(0).description());
        assertEquals("3%", spec.minorTraits().get(0).facts().get(0).value());
        var repository = new BuildMetadataRepository();
        assertEquals(ICON, repository.findDetailImageSource(model.BuildResourceType.SPECIALIZATIONS, 7, "background").orElseThrow());
        assertEquals(ICON, repository.findDetailImageSource(model.BuildResourceType.TRAITS, 701, "fact-0").orElseThrow());
        assertTrue(repository.findDetailImageSource(model.BuildResourceType.TRAITS, 701, "fact-1").isEmpty());
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
        assertEquals(List.of(701, 702, 703), s7.minorTraits().stream().map(x -> x.id()).toList(), "offered ids keep upstream list order");
        assertFalse(s7.minorTraits().get(1).resolved(), "choice upstream did not describe stays explicit");
        assertTrue(s7.majorTraits().get(0).resolved());
        assertEquals(1, s7.majorTraits().get(0).tier());
        assertEquals("Major", s7.majorTraits().get(0).slot());
        assertEquals(7, s7.majorTraits().get(0).specializationId());
        assertEquals(0, s7.majorTraits().get(0).order());
        assertEquals("ORDINARY", s7.majorTraits().get(0).placement());
        assertEquals("UNRESOLVED", s7.minorTraits().get(1).placement());
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

    // ---- supervisor HOLD rework ----

    /** Every closure trait of specs 7 and 9, complete (spec, tier, order, slot). */
    private static String allTraits() {
        StringBuilder b = new StringBuilder("[");
        int[][] t = {{701, 7, 1, 0, 0}, {702, 7, 2, 0, 0}, {703, 7, 3, 0, 0}, {711, 7, 1, 0, 1}, {712, 7, 1, 1, 1},
                {713, 7, 1, 2, 1}, {721, 7, 2, 0, 1}, {722, 7, 2, 1, 1}, {723, 7, 2, 2, 1}, {731, 7, 3, 0, 1},
                {732, 7, 3, 1, 1}, {733, 7, 3, 2, 1}, {901, 9, 1, 0, 0}, {911, 9, 1, 0, 1}};
        for (int[] r : t) {
            if (b.length() > 1) b.append(',');
            b.append("{\"id\":").append(r[0]).append(",\"name\":\"Tr").append(r[0]).append("\",\"specialization\":")
                    .append(r[1]).append(",\"tier\":").append(r[2]).append(",\"order\":").append(r[3])
                    .append(",\"slot\":\"").append(r[4] == 0 ? "Minor" : "Major").append("\"}");
        }
        return b.append(']').toString();
    }

    private void exec(String sql) throws Exception {
        try (Statement st = admin.createStatement()) { st.execute(sql); }
    }

    @Test
    void completeWarmFixtureFetchesNothingAtAll() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        FakeSource source = fullSource();
        source.bodies.put("traits", allTraits());
        var repair = new BuildMetadataRepair(source, NOW);
        repair.repairStored(A);
        assertEquals(5, source.calls.size());
        source.calls.clear();
        repair.repairStored(A);
        assertEquals(List.of(), source.calls, "complete warm rows need zero upstream requests");
    }

    @Test
    void incompleteStoredRowsAreRepairedWithoutTouchingTheAccountPublication() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        long gen = generation(A);
        long tabs = count("SELECT count(*) FROM character_build_tab_specializations");
        // earlier partial rows: no profession, no tier/order, empty skill name is impossible (NOT NULL)
        exec("INSERT INTO build_specializations VALUES (7,'Old7',NULL,false,NULL,'{701}','{711}',now())");
        exec("INSERT INTO build_traits (trait_id,name,specialization_id,tier,trait_order,slot,fetched_at) "
                + "VALUES (701,'Old701',7,NULL,NULL,'Minor',now())");
        FakeSource source = fullSource();
        source.bodies.put("traits", allTraits());
        new BuildMetadataRepair(source, NOW).repairStored(A);
        assertTrue(source.calls.contains("specializations?7,9") || source.calls.contains("specializations?7"), source.calls.toString());
        assertEquals(1, count("SELECT count(*) FROM build_specializations WHERE specialization_id = 7 AND profession = 'Ranger'"));
        assertEquals(1, count("SELECT count(*) FROM build_traits WHERE trait_id = 701 AND tier = 1 AND trait_order = 0 AND name = 'Tr701'"));
        assertEquals(gen, generation(A));
        assertEquals(tabs, count("SELECT count(*) FROM character_build_tab_specializations"));
    }

    @Test
    void partialOrMalformedReplacementsNeverEraseGoodFields() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        FakeSource good = fullSource();
        good.bodies.put("traits", allTraits());
        new BuildMetadataRepair(good, NOW).repairStored(A);
        // force a refetch of spec 7 and trait 701 by making them incomplete, then answer partially
        exec("UPDATE build_specializations SET profession = NULL WHERE specialization_id = 7");
        exec("UPDATE build_traits SET tier = NULL WHERE trait_id = 701");
        FakeSource partial = new FakeSource();
        partial.bodies.put("specializations", "[{\"id\":7,\"name\":\"X\",\"elite\":false,\"minor_traits\":[1],\"major_traits\":[2]}]");
        partial.bodies.put("traits", "[{\"id\":701,\"name\":\"X\",\"specialization\":7,\"order\":0,\"slot\":\"Minor\"}]");
        new BuildMetadataRepair(partial, NOW).repairStored(A);
        assertEquals("Spec7", scalar("SELECT name FROM build_specializations WHERE specialization_id = 7"));
        assertEquals("Tr701", scalar("SELECT name FROM build_traits WHERE trait_id = 701"));
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, read(A, A_ID, "Ana").state());
    }

    private String scalar(String sql) throws Exception {
        try (var st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    @Test
    void placementKeepsTierZeroOrderZeroAndFlagsWrongOwnerSlotAndDuplicates() throws Exception {
        publishRoster(A, "Ana");
        String tabs = TABS.replace("[701,null,703]", "[713,null,701]");
        CharacterBuildTabsSync.sync(A, url -> url.endsWith("/v2/account")
                ? M.readTree("{\"id\":\"" + A_ID + "\"}") : M.readTree(tabs));
        FakeSource source = fullSource();
        source.bodies.put("traits", "["
                + "{\"id\":701,\"name\":\"a\",\"specialization\":7,\"tier\":0,\"order\":0,\"slot\":\"Minor\"},"
                + "{\"id\":702,\"name\":\"wrong owner\",\"specialization\":8,\"tier\":2,\"order\":0,\"slot\":\"Minor\"},"
                + "{\"id\":703,\"name\":\"wrong slot\",\"specialization\":7,\"tier\":3,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":711,\"name\":\"dup1\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":712,\"name\":\"dup2\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":713,\"name\":\"ok\",\"specialization\":7,\"tier\":1,\"order\":2,\"slot\":\"Major\"},"
                + "{\"id\":721,\"name\":\"odd\",\"specialization\":7,\"tier\":5,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":723,\"name\":\"order3\",\"specialization\":7,\"tier\":2,\"order\":3,\"slot\":\"Major\"},"
                + "{\"id\":731,\"name\":\"t3o0\",\"specialization\":7,\"tier\":3,\"order\":0,\"slot\":\"Major\"}]");
        new BuildMetadataRepair(source, NOW).repairStored(A);
        var s7 = body(read(A, A_ID, "Ana")).tabs().get(0).specializations().get(0);
        var minor = s7.minorTraits();
        assertEquals("NONSTANDARD", minor.get(0).placement(), "tier 0 is outside ordinary tiers 1..3");
        assertEquals(0, minor.get(0).tier(), "tier 0 is kept, not nulled");
        assertEquals(0, minor.get(0).order(), "order 0 is kept");
        assertEquals(7, minor.get(0).specializationId());
        assertEquals("CONFLICT", minor.get(1).placement(), "wrong owning specialization");
        assertEquals(702, minor.get(1).id(), "id preserved");
        assertEquals("CONFLICT", minor.get(2).placement(), "Major trait offered as a minor choice");
        var major = s7.majorTraits();
        assertEquals("CONFLICT", major.get(0).placement(), "duplicate tier/order");
        assertEquals("CONFLICT", major.get(1).placement(), "duplicate tier/order");
        assertEquals("ORDINARY", major.get(2).placement(), "valid sibling stays ordinary");
        assertEquals("NONSTANDARD", major.get(3).placement());
        assertEquals("UNRESOLVED", major.get(4).placement());
        assertEquals("NONSTANDARD", major.get(5).placement(), "order 3 is outside documented order 0..2");
        assertEquals(3, major.get(5).order(), "raw order kept");
        assertEquals("ORDINARY", major.get(6).placement(), "tier 3 / order 0 is ordinary");
        assertEquals("ORDINARY", s7.traits().get(0).placement(), "valid selected major");
        assertEquals("CONFLICT", s7.traits().get(2).placement(), "selected Minor in a major selection");
    }

    @Test
    void persistedIncompleteRowsStayUnresolvedAfterFailedRepairWhileCompleteSiblingsResolve() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        exec("INSERT INTO build_specializations VALUES (7,'Old7',NULL,false,NULL,'{701,703}','{711}',now())");
        exec("INSERT INTO build_specializations VALUES (9,'Spec9','Ranger',true,NULL,'{}','{}',now())");
        exec("INSERT INTO build_traits (trait_id,name,specialization_id,tier,trait_order,slot,fetched_at) "
                + "VALUES (701,'Old701',7,NULL,NULL,'Minor',now()),(703,'Tr703',7,3,0,'Minor',now())");
        FakeSource down = fullSource();
        down.fail = true;
        new BuildMetadataRepair(down, NOW).repairStored(A);
        var specs = body(read(A, A_ID, "Ana")).tabs().get(0).specializations();
        assertFalse(specs.get(0).specialization().resolved(), "incomplete specialization stays unresolved");
        assertEquals(7, specs.get(0).specialization().id());
        assertNull(specs.get(0).minorTraits());
        assertEquals(701, specs.get(0).traits().get(0).id(), "original id preserved");
        assertFalse(specs.get(0).traits().get(0).resolved(), "incomplete trait stays unresolved");
        assertEquals("UNRESOLVED", specs.get(0).traits().get(0).placement());
        assertTrue(specs.get(2).specialization().resolved(), "complete sibling stays resolved");
    }

    @Test
    void selectedTraitInheritsDuplicateConflictFromOfferedMajorChoices() throws Exception {
        publishRoster(A, "Ana");
        String tabs = TABS.replace("[701,null,703]", "[711,712,731]");
        CharacterBuildTabsSync.sync(A, url -> url.endsWith("/v2/account")
                ? M.readTree("{\"id\":\"" + A_ID + "\"}") : M.readTree(tabs));
        FakeSource source = fullSource();
        source.bodies.put("traits", "["
                + "{\"id\":711,\"name\":\"dup1\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":712,\"name\":\"dup2\",\"specialization\":7,\"tier\":1,\"order\":0,\"slot\":\"Major\"},"
                + "{\"id\":731,\"name\":\"clean\",\"specialization\":7,\"tier\":3,\"order\":0,\"slot\":\"Major\"}]");
        new BuildMetadataRepair(source, NOW).repairStored(A);
        var s7 = body(read(A, A_ID, "Ana")).tabs().get(0).specializations().get(0);
        assertEquals("CONFLICT", s7.traits().get(0).placement());
        assertEquals("CONFLICT", s7.traits().get(1).placement());
        assertEquals("ORDINARY", s7.traits().get(2).placement(), "clean sibling stays ordinary");
        assertEquals("CONFLICT", s7.majorTraits().get(0).placement());
    }

    // ---- production wiring: AccountRefreshGateway.syncCharacterBuildTabs ----

    private AccountRefreshGateway gateway(FakeSource source) {
        return new AccountRefreshGateway() {
            @Override protected JsonNode fetchBuildMetadata(String endpoint, List<String> ids) throws Exception {
                return source.fetch(endpoint, ids);
            }
        };
    }

    @Test
    void gatewayRepairsStoredRefsEvenWhenTheBuildFetchThenFails() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        long gen = generation(A);
        long rows = count("SELECT count(*) FROM character_build_tabs");
        FakeSource source = fullSource();
        assertThrows(Exception.class, () -> gateway(source).syncCharacterBuildTabs(A, url -> { throw new java.io.IOException("HTTP 503"); }));
        assertEquals(1, count("SELECT count(*) FROM build_skills WHERE skill_id = 7"), "pre-pass repaired metadata");
        assertEquals(gen, generation(A));
        assertEquals(rows, count("SELECT count(*) FROM character_build_tabs"));
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, read(A, A_ID, "Ana").state());
    }

    @Test
    void gatewayMetadataFailuresBeforeAndAfterAValidBuildRefreshAreNonFatal() throws Exception {
        publishRoster(A, "Ana");
        FakeSource down = fullSource();
        down.fail = true;
        gateway(down).syncCharacterBuildTabs(A, url -> url.endsWith("/v2/account")
                ? M.readTree("{\"id\":\"" + A_ID + "\"}") : M.readTree(TABS));
        assertEquals(CharacterBuildTabsService.State.AVAILABLE, read(A, A_ID, "Ana").state());
        assertFalse(body(read(A, A_ID, "Ana")).tabs().get(0).skills().heal().resolved());
        assertTrue(down.calls.size() >= 1, "post-publication repair was attempted");
    }

    @Test
    void gatewayRepairsReferencesAddedByTheJustPublishedBuilds() throws Exception {
        publishRoster(A, "Ana");
        FakeSource source = fullSource();
        gateway(source).syncCharacterBuildTabs(A, url -> url.endsWith("/v2/account")
                ? M.readTree("{\"id\":\"" + A_ID + "\"}") : M.readTree(TABS));
        assertTrue(source.calls.contains("skills?2,4,5,7,40"), source.calls.toString());
        assertTrue(body(read(A, A_ID, "Ana")).tabs().get(0).skills().heal().resolved());
    }

    // ---- ordinary GET: app-relative URLs, upstream-free; bounded SQL and size evidence ----

    @Test
    void ordinaryReadIsUpstreamFreeAppRelativeAndSqlBounded() throws Exception {
        publishRoster(A, "Ana");
        publishBuilds(A, A_ID);
        FakeSource source = fullSource();
        source.bodies.put("traits", allTraits());
        var repair = new BuildMetadataRepair(source, NOW);
        long t0 = System.nanoTime();
        repair.repairStored(A);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;
        int coldCalls = source.calls.size();
        t0 = System.nanoTime();
        repair.repairStored(A);
        long warmMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(coldCalls, source.calls.size(), "warm: no extra requests");

        int[] statements = {0};
        var service = new CharacterBuildTabsService(null, new CharacterBuildTabsRepository(), () -> {
            final Connection c;
            try { c = connect(); c.setSchema(schema); }
            catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); }
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("prepareStatement") || method.getName().equals("createStatement")) statements[0]++;
                        try { return method.invoke(c, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
        }, Clock.systemUTC(), Duration.ofHours(1));
        var ctx = new AccountContext(A_ID, EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.INVENTORIES, AccountGrant.BUILDS), Set.of(), "ctx", Instant.now().plusSeconds(60));
        int callsBeforeRead = source.calls.size();
        var result = service.read(ctx, A, "Ana");
        assertEquals(callsBeforeRead, source.calls.size(), "GET never calls upstream");
        String json = M.copy().disable(com.fasterxml.jackson.databind.MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES).writeValueAsString(CharacterBuildTabsResponse.respond(result).getBody());
        assertFalse(json.contains("http"), "only application-relative image URLs");
        assertTrue(json.contains("\"iconUrl\":\"/api/resources/"));
        // metadata adds at most one statement per namespace (spec, trait, skill, pet, legend) to the core read
        assertTrue(statements[0] <= 20, "statements: " + statements[0]);
        long bytes = count("SELECT pg_total_relation_size('build_specializations') + pg_total_relation_size('build_traits')"
                + " + pg_total_relation_size('build_skills') + pg_total_relation_size('build_pets') + pg_total_relation_size('build_legends')");
        System.out.println("BUILD-METADATA-MEASUREMENT fixture=2 specs,14 traits(closure),5 skills,1 pet,1 legend; rawRefs(specs/selectedTraits/skills+swap/pets/legends)=2/2/4+1/1/1 "
                + "; coldRequests=" + coldCalls + " coldMs=" + coldMs + " warmRequests=0 warmMs=" + warmMs
                + " responseBytes=" + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " getSqlStatements=" + statements[0] + " metadataRelationBytesInclIndexes=" + bytes
                + " rows(spec/trait/skill/pet/legend)=" + count("SELECT count(*) FROM build_specializations") + "/"
                + count("SELECT count(*) FROM build_traits") + "/" + count("SELECT count(*) FROM build_skills") + "/"
                + count("SELECT count(*) FROM build_pets") + "/" + count("SELECT count(*) FROM build_legends"));
    }

    private long generation(AccountScope s) throws Exception {
        try (Connection c = connect()) {
            c.setSchema(schema);
            return new repo.AccountSourceStateRepository().read(c, s, "CHARACTER_BUILD_TABS").map(x -> x.generation()).orElse(0L);
        }
    }
}
