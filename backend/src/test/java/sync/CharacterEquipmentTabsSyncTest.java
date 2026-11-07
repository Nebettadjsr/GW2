package sync;

import application.CharacterEquipmentTabsService;
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
import repo.CharacterEquipmentTabsRepository;
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
class CharacterEquipmentTabsSyncTest {
    private static final String A_ID = "10000000-0000-0000-0000-0000000000a1";
    private static final String B_ID = "10000000-0000-0000-0000-0000000000b2";
    private static final AccountScope A = AccountScope.of(A_ID);
    private static final AccountScope B = AccountScope.of(B_ID);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_equipment_tabs_" + UUID.randomUUID().toString().replace("-", "");
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

    private static final String TABS = """
            [{"tab":2,"name":"Alt","is_active":false,"equipment":[{"id":100,"slot":"Helm","upgrades":[300],"infusions":[301]}]},
             {"tab":1,"name":"Main","is_active":true,"equipment":[
               {"id":100,"slot":"Helm","location":"Equipped","binding":"Character","bound_to":"Ana",
                "stats":{"id":161,"attributes":{"Power":10}}},
               {"id":200,"slot":"Boots","upgrades":[300]}]}]""";

    private AccountSync.AuthFetcher fetcher(String accountId, List<String> urls, Map<String, String> bodies) {
        return url -> {
            urls.add(url);
            if (url.endsWith("/v2/account")) return M.readTree("{\"id\":\"" + accountId + "\"}");
            for (var e : bodies.entrySet()) if (url.contains(e.getKey())) return M.readTree(e.getValue());
            throw new IllegalStateException("unexpected " + url);
        };
    }

    private Set<Integer> repaired = new TreeSet<>();
    private int repairCalls;
    private final IconSync.ItemMetadataRepair repair = ids -> { repairCalls++; repaired.addAll(ids); };

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

    private CharacterEquipmentTabsService service(Duration maxAge) {
        return new CharacterEquipmentTabsService(null, new CharacterEquipmentTabsRepository(), () -> {
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
        assertEquals("https://api.guildwars2.com/v2/characters/Scribe%20Ana%2F%C3%A9/equipmenttabs"
                + "?tabs=all&v=2025-08-29T01:00:00.000Z", CharacterEquipmentTabsSync.url("Scribe Ana/é"));
    }

    @Test
    void missingParentRejectsBeforeAnyTabFetchAndAdvancesNothing() throws Exception {
        List<String> urls = new ArrayList<>();
        assertThrows(CharacterEquipmentTabsSync.PrerequisiteUnavailableException.class,
                () -> CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, urls, Map.of()), repair));
        assertTrue(urls.stream().noneMatch(u -> u.contains("equipmenttabs")));
        assertEquals(0, repairCalls);
        assertEquals(0, count("SELECT count(*) FROM account_source_state"));
    }

    @Test
    void publishesTabsInUpstreamOrderWithoutTouchingCharactersOrInventoryAndReadsThemBack() throws Exception {
        publishRoster(A, "Ana", "Bo");
        long parentGen = generation(A, "CHARACTERS");
        long itemsBefore = count("SELECT coalesce(sum(count),0) FROM character_items");
        List<String> urls = new ArrayList<>();
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("/characters/Ana/equipmenttabs", TABS);
        bodies.put("/characters/Bo/equipmenttabs", "[]");
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, urls, bodies), repair);

        assertTrue(urls.contains("https://api.guildwars2.com/v2/characters/Ana/equipmenttabs?tabs=all&v=2025-08-29T01:00:00.000Z"));
        assertEquals(1, repairCalls, "one batched metadata repair");
        assertEquals(new TreeSet<>(List.of(100, 200, 300, 301)), repaired, "deduplicated ids incl. upgrades and infusions");
        assertEquals(parentGen, generation(A, "CHARACTERS"), "CHARACTERS untouched");
        assertEquals(1, generation(A, "CHARACTER_EQUIPMENT_TABS"));
        assertEquals(itemsBefore, count("SELECT coalesce(sum(count),0) FROM character_items"));

        try (var ps = admin.prepareStatement("INSERT INTO items(item_id,name,type,rarity) VALUES (100,'Helm','Armor','Legendary'),(200,'Boots','Armor','Ascended')")) {
            ps.executeUpdate();
        }
        var r = service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(CharacterEquipmentTabsService.State.AVAILABLE, r.state());
        assertEquals(parentGen, r.coveredParentGeneration());
        assertEquals(List.of(2, 1), r.tabs().stream().map(t -> t.number()).toList());
        var main = r.tabs().get(1);
        assertTrue(main.active());
        assertEquals("Legendary", main.equipment().get(0).item().rarity());
        assertEquals(10, main.equipment().get(0).statAttributes().get("Power"));
        assertEquals("Ana", main.equipment().get(0).boundTo());
        assertFalse(main.equipment().get(1).upgrades().get(0).resolved(), "unknown metadata stays an explicit unresolved ref");
        assertEquals(300, main.equipment().get(1).upgrades().get(0).itemId());
        assertEquals(List.of(), service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Bo").tabs(), "valid empty tabs");
    }

    @Test
    void emptyEquipmentAndEmptyRosterAreValidSuccesses() throws Exception {
        publishRoster(A, "Ana");
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(),
                Map.of("/characters/Ana/", "[{\"tab\":1,\"is_active\":true,\"equipment\":[]}]")), repair);
        var r = service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(List.of(), r.tabs().get(0).equipment());

        publishRoster(A);
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of()), repair);
        assertEquals(2, generation(A, "CHARACTER_EQUIPMENT_TABS"));
        assertEquals(0, count("SELECT count(*) FROM character_equipment_tabs"));
    }

    @Test
    void malformedCharacterPublishesNothingAndKeepsLastGood() throws Exception {
        publishRoster(A, "Ana", "Bo");
        Map<String, String> good = new LinkedHashMap<>();
        good.put("/characters/Ana/", TABS);
        good.put("/characters/Bo/", "[]");
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), good), repair);
        long tabs = count("SELECT count(*) FROM character_equipment_tabs");
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("/characters/Ana/", "[]");
        bad.put("/characters/Bo/", "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":0}]}]");
        assertThrows(IllegalStateException.class,
                () -> CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), bad), repair));
        assertEquals(tabs, count("SELECT count(*) FROM character_equipment_tabs"));
        assertEquals(1, generation(A, "CHARACTER_EQUIPMENT_TABS"));
    }

    @Test
    void publicationFailureRollsBackRowsMarkerAndCoverageTogether() throws Exception {
        publishRoster(A, "Ana");
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", TABS)), repair);
        long tabs = count("SELECT count(*) FROM character_equipment_tabs");
        try (Statement s = admin.createStatement()) {
            s.execute("ALTER TABLE character_equipment_coverage ADD CONSTRAINT boom CHECK (parent_generation < 0) NOT VALID");
        }
        assertThrows(Exception.class, () -> CharacterEquipmentTabsSync.sync(A,
                fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[]")), repair));
        assertEquals(tabs, count("SELECT count(*) FROM character_equipment_tabs"));
        assertEquals(1, generation(A, "CHARACTER_EQUIPMENT_TABS"));
    }

    @Test
    void parentGenerationChangeDuringFetchPublishesNothing() throws Exception {
        publishRoster(A, "Ana");
        var capture = new CharacterEquipmentTabsSync.Capture(generation(A, "CHARACTERS"), List.of("Ana"));
        publishRoster(A, "Ana"); // CHARACTERS advances after the capture
        try (Connection c = connect()) {
            c.setSchema(schema);
            assertThrows(CharacterEquipmentTabsSync.ParentChangedException.class, () -> CharacterEquipmentTabsSync
                    .publish(c, A, capture, Map.of("Ana", List.of()), NOW));
        }
        assertEquals(0, generation(A, "CHARACTER_EQUIPMENT_TABS"));
    }

    @Test
    void iconPresentNameKnownRarityMissingStaysUnresolvedThroughRepairSelectionAndMapping() throws Exception {
        publishRoster(A, "Ana");
        String icon = "https://render.guildwars2.com/file/0123456789ABCDEF0123456789ABCDEF01234567/1.png";
        try (var ps = admin.prepareStatement(
                "INSERT INTO items(item_id,name,type,icon_url) VALUES (100,'Helm','Armor',?),(200,'Boots','Armor','x')")) {
            ps.setString(1, icon);
            ps.executeUpdate();
        }
        // real production selection against the real schema, with a source that knows nothing
        try (Connection c = connect()) {
            c.setSchema(schema);
            var failed = IconSync.repairMissingMetadata(c, List.of(100), ids -> M.readTree("[]"));
            assertTrue(failed.isEmpty());
            var requested = new ArrayList<List<Integer>>();
            IconSync.repairMissingMetadata(c, List.of(100, 100), ids -> { requested.add(ids); return M.readTree("[]"); });
            assertEquals(List.of(List.of(100)), requested, "icon-present but rarity-less row is selected for repair");
        }
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":100,\"slot\":\"Helm\"}]}]")), repair);
        var item = service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Ana").tabs().get(0).equipment().get(0).item();
        assertFalse(item.resolved(), "name without rarity is not resolved");
        assertEquals("Helm", item.name());
        assertNull(item.rarity());
        assertEquals(icon, item.icon(), "icon presentation stays independent");
    }

    @Test
    void firstEquipmentRefreshFailureIsUnavailableUnlessRetryingAndKeepsLastGood() throws Exception {
        publishRoster(A, "Ana");
        boolean[] flags = new boolean[2]; // refreshing, failed
        var status = new application.CharacterRosterService.RefreshStatus() {
            public boolean isRefreshing(AccountScope s, String src) { return flags[0]; }
            public boolean lastRefreshFailed(AccountScope s, String src) { return flags[1]; }
        };
        var svc = new CharacterEquipmentTabsService(status, new CharacterEquipmentTabsRepository(), () -> {
            try { Connection c = connect(); c.setSchema(schema); return c; }
            catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); }
        }, Clock.systemUTC(), Duration.ofHours(1));
        assertEquals("COVERAGE_NOT_PUBLISHED", svc.read(ctx(A_ID, ALL), A, "Ana").reason(), "never attempted");
        flags[1] = true;
        var failed = svc.read(ctx(A_ID, ALL), A, "Ana");
        assertEquals(CharacterEquipmentTabsService.State.UNAVAILABLE, failed.state());
        assertEquals("REFRESH_FAILED", failed.reason());
        assertNull(failed.tabs());
        flags[0] = true;
        assertEquals("REFRESH_IN_PROGRESS", svc.read(ctx(A_ID, ALL), A, "Ana").reason(), "active retry stays pending");
        flags[0] = false;
        // stale compatible last-good after a later failure
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[]")), repair);
        assertEquals("EQUIPMENT_REFRESH_FAILED", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        // parent missing stays independent of an equipment outage
        assertEquals("PARENT_NOT_PUBLISHED", svc.read(ctx(B_ID, ALL), B, "Ana").reason());
    }

    @Test
    void readDistinguishesMissingIncompatibleStaleGrantsAndNotFound() throws Exception {
        var svc = service(Duration.ofHours(1));
        assertEquals("PARENT_NOT_PUBLISHED", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        publishRoster(A, "Ana");
        assertEquals("COVERAGE_NOT_PUBLISHED", svc.read(ctx(A_ID, ALL), A, "Ana").reason());
        assertEquals(CharacterEquipmentTabsService.State.NOT_FOUND, svc.read(ctx(A_ID, ALL), A, "Nobody").state());
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/", "[]")), repair);
        assertEquals(CharacterEquipmentTabsService.State.AVAILABLE, svc.read(ctx(A_ID, ALL), A, "Ana").state());
        assertEquals("EQUIPMENT_SOURCE_EXPIRED",
                service(Duration.ofSeconds(-1)).read(ctx(A_ID, ALL), A, "Ana").reason());
        assertEquals("EQUIPMENT_GRANT_REQUIRED", svc.read(ctx(A_ID, AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.INVENTORIES), A, "Ana").reason());
        assertEquals("PARENT_GRANT_REQUIRED", svc.read(ctx(A_ID, AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
                AccountGrant.BUILDS), A, "Ana").reason());

        publishRoster(A, "Ana"); // ordinary CHARACTERS refresh succeeds and makes coverage incompatible
        var r = svc.read(ctx(A_ID, ALL), A, "Ana");
        assertEquals("COVERAGE_INCOMPATIBLE", r.reason());
        assertNull(r.tabs());
        assertEquals(1, generation(A, "CHARACTER_EQUIPMENT_TABS"), "no automatic tabs fetch");
    }

    @Test
    void accountsWithTheSameCharacterNameStayIsolated() throws Exception {
        publishRoster(A, "Same");
        publishRoster(B, "Same");
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Same/", TABS)), repair);
        CharacterEquipmentTabsSync.sync(B, fetcher(B_ID, new ArrayList<>(), Map.of("/characters/Same/", "[]")), repair);
        long bBefore = count("SELECT count(*) FROM character_equipment_tabs WHERE account_id = ?::uuid", B_ID);
        assertEquals(0, bBefore);
        CharacterEquipmentTabsSync.sync(B, fetcher(B_ID, new ArrayList<>(), Map.of("/characters/Same/", "[]")), repair);
        assertEquals(2, count("SELECT count(*) FROM character_equipment_tabs WHERE account_id = ?::uuid", A_ID));
        assertEquals(1, generation(A, "CHARACTER_EQUIPMENT_TABS"));
        assertEquals(2, generation(B, "CHARACTER_EQUIPMENT_TABS"));
        assertEquals(2, svcTabs(A_ID, A));
        assertEquals(0, svcTabs(B_ID, B));
    }

    private int svcTabs(String id, AccountScope scope) throws Exception {
        return service(Duration.ofHours(1)).read(ctx(id, ALL), scope, "Same").tabs().size();
    }
}
