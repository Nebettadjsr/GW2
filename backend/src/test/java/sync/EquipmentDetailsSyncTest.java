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

/** STORY-CHAR-014: stored equipment details and derived totals; disposable schema, fixture upstream. */
class EquipmentDetailsSyncTest {
    private static final String A_ID = "10000000-0000-0000-0000-0000000000a1";
    private static final String B_ID = "10000000-0000-0000-0000-0000000000b2";
    private static final AccountScope A = AccountScope.of(A_ID);
    private static final AccountScope B = AccountScope.of(B_ID);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_equipment_details_" + UUID.randomUUID().toString().replace("-", "");
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


    private static final String TABS = """
            [{"tab":1,"name":"Main","is_active":true,"equipment":[
              {"id":100,"slot":"Helm","upgrades":[300],"infusions":[301],"stats":{"id":161,"attributes":{"Power":10}}},
              {"id":200,"slot":"Boots","upgrades":[300],"stats":{"id":162}},
              {"id":500,"slot":"WeaponA1"},{"id":600,"slot":"WeaponB1"}]}]""";

    private static final String ITEMS = """
            [{"id":100,"type":"Armor","description":"<c=@flavor>Nice</c>","level":80,"details":{"weight_class":"Heavy","type":"Helm","defense":50,
               "infix_upgrade":{"id":161,"attributes":[{"attribute":"Power","modifier":99}]}}},
             {"id":200,"type":"Armor","details":{"weight_class":"Heavy","type":"Boots","defense":30,"attribute_adjustment":100,"infix_upgrade":{"id":162,"attributes":[]}}},
             {"id":500,"type":"Weapon","details":{"type":"Sword","min_power":900,"max_power":1000,
               "infix_upgrade":{"attributes":[{"attribute":"Vitality","modifier":20}]}}},
             {"id":600,"type":"Weapon","details":{"type":"Axe","infix_upgrade":{"attributes":[{"attribute":"Power","modifier":7}]}}},
             {"id":300,"type":"UpgradeComponent","details":{"type":"Rune","bonuses":["+25 Power","+35 Ferocity"]}},
             {"id":301,"type":"UpgradeComponent","details":{"type":"Default","infix_upgrade":{"attributes":[{"attribute":"AgonyResistance","modifier":1}],
               "buff":{"description":"+1 Agony Resistance"}}}}]""";
    private static final String STATS = """
            [{"id":161,"attributes":[{"attribute":"Power","multiplier":1.0}]},
             {"id":162,"attributes":[{"attribute":"Precision","multiplier":0.5,"value":3}]}]""";

    private int itemCalls, statCalls;
    private final EquipmentDetailsSync.Source good = new EquipmentDetailsSync.Source() {
        public com.fasterxml.jackson.databind.JsonNode items(List<Integer> ids) throws Exception { itemCalls++; return M.readTree(ITEMS); }
        public com.fasterxml.jackson.databind.JsonNode itemStats(List<Integer> ids) throws Exception { statCalls++; return M.readTree(STATS); }
    };

    private void seed() throws Exception {
        publishRoster(A, "Ana");
        try (var ps = admin.prepareStatement("INSERT INTO items(item_id,name,type,rarity) VALUES "
                + "(100,'Helm','Armor','Legendary'),(200,'Boots','Armor','Ascended'),(500,'Sword','Weapon','Ascended'),"
                + "(600,'Axe','Weapon','Ascended'),(300,'Rune','UpgradeComponent','Exotic'),(301,'Inf','UpgradeComponent','Exotic')")) {
            ps.executeUpdate();
        }
    }

    private void sync(EquipmentDetailsSync.Source source) throws Exception {
        CharacterEquipmentTabsSync.sync(A, fetcher(A_ID, new ArrayList<>(), Map.of("/characters/Ana/equipmenttabs", TABS)),
                repair, source);
    }

    private CharacterEquipmentTabsService.Result read() throws Exception {
        return service(Duration.ofHours(1)).read(ctx(A_ID, ALL), A, "Ana");
    }

    @Test
    void storesDetailsResolvesSelectedStatsAndComputesBothSetsOnceEach() throws Exception {
        seed();
        sync(good);
        assertEquals(1, itemCalls);
        assertEquals(1, statCalls);
        var tab = read().tabs().get(0);
        var helm = tab.equipment().get(0);
        assertEquals(Map.of("Power", 10), helm.statAttributes(), "selected attributes override fixed ones");
        assertEquals("Nice", helm.item().details().description(), "markup stripped");
        assertEquals(2, helm.runeCount());
        assertEquals(Map.of("Precision", 53), tab.equipment().get(1).statAttributes(), "round(100*0.5+3), not doubled");
        assertEquals(Map.of("AgonyResistance", 1), helm.infusions().get(0).details().attributes());
        var a = tab.stats().weaponSets().get("A");
        assertTrue(a.complete(), a.warnings().toString());
        assertEquals(1035, a.values().get("Power"), "10 selected + rune tiers counted once, not per item");
        assertEquals(1020, a.values().get("Vitality"));
        assertEquals(1053, a.values().get("Precision"));
        assertEquals(1080, a.values().get("Armor"));
        assertEquals(1, a.values().get("AgonyResistance"));
        assertEquals(5922 + 10200, a.values().get("Health"));
        var b = tab.stats().weaponSets().get("B");
        assertEquals(1000, b.values().get("Vitality"), "inactive set A weapon excluded");
        assertEquals(1042, b.values().get("Power"), "set B weapon adds its own +7");
    }

    @Test
    void warmCacheMakesNoUpstreamCallAndFailureOrMalformedDataKeepsStoredRows() throws Exception {
        seed();
        sync(good);
        sync(good);
        assertEquals(1, itemCalls, "complete cache: zero further calls");
        assertEquals(1, statCalls);
        try (var ps = admin.prepareStatement("DELETE FROM equipment_item_details WHERE item_id = 500")) { ps.executeUpdate(); }
        var broken = new EquipmentDetailsSync.Source() {
            public com.fasterxml.jackson.databind.JsonNode items(List<Integer> ids) throws Exception {
                return M.readTree("[{\"id\":500,\"type\":\"Weapon\",\"details\":{\"infix_upgrade\":{\"attributes\":[{\"attribute\":\"Power\"}]}}}]");
            }
            public com.fasterxml.jackson.databind.JsonNode itemStats(List<Integer> ids) { throw new IllegalStateException("down"); }
        };
        sync(broken);
        assertEquals(3, generation(A, "CHARACTER_EQUIPMENT_TABS"), "publication still succeeded each time");
        assertEquals(5, count("SELECT count(*) FROM equipment_item_details"), "malformed row not stored, good rows kept");
        var a = read().tabs().get(0).stats().weaponSets().get("A");
        assertFalse(a.complete(), "missing metadata is reported, not hidden");
        assertFalse(a.warnings().isEmpty());
    }

    @Test
    void failingMetadataIsNonfatalAndTotalsAreIncomplete() throws Exception {
        seed();
        var down = new EquipmentDetailsSync.Source() {
            public com.fasterxml.jackson.databind.JsonNode items(List<Integer> ids) { throw new IllegalStateException("down"); }
            public com.fasterxml.jackson.databind.JsonNode itemStats(List<Integer> ids) { throw new IllegalStateException("down"); }
        };
        sync(down);
        var r = read();
        assertEquals(CharacterEquipmentTabsService.State.AVAILABLE, r.state());
        assertFalse(r.tabs().get(0).stats().weaponSets().get("A").complete());
        assertNull(r.tabs().get(0).equipment().get(0).item().details());
    }

    @Test
    void bareFractionalAndPartialRecordsAreNeverCompleteAndRepairRecovers() throws Exception {
        seed();
        var bad = new EquipmentDetailsSync.Source() {
            public com.fasterxml.jackson.databind.JsonNode items(List<Integer> ids) throws Exception {
                return M.readTree("""
                        [{"id":100},{"id":200.5,"type":"Armor","details":{"type":"Boots","weight_class":"Heavy","defense":1}},
                         {"id":500,"type":"Weapon","details":{"type":"Sword","infix_upgrade":{"attributes":[{"attribute":"Power","modifier":1.5}]}}},
                         {"id":600,"type":"Weapon"}]""");
            }
            public com.fasterxml.jackson.databind.JsonNode itemStats(List<Integer> ids) throws Exception { return M.readTree("[]"); }
        };
        sync(bad);
        assertEquals(0, count("SELECT count(*) FROM equipment_item_details"), "nothing incomplete is cached");
        // An incomplete legacy row is not read as known and is retried; a failed repair keeps it honest.
        try (var ps = admin.prepareStatement("INSERT INTO equipment_item_details(item_id,details,fetched_at) "
                + "VALUES (100,'{\"itemId\":100}'::jsonb, now())")) { ps.executeUpdate(); }
        sync(bad);
        var r = read();
        assertNull(r.tabs().get(0).equipment().get(0).item().details());
        assertFalse(r.tabs().get(0).stats().weaponSets().get("A").complete());
        sync(good);
        assertEquals(1, count("SELECT count(*) FROM equipment_item_details WHERE item_id = 100 AND details->>'format' IS NOT NULL"),
                "the incomplete row was replaced");
        assertTrue(read().tabs().get(0).stats().weaponSets().get("A").complete());
    }

    @Test
    void selectedStatIdOverridesFixedAttributesThroughItemstats() throws Exception {
        seed();
        sync(good);
        // Entry 'Boots' selects stat 162; the item's fixed set (stat 161 Power 99) must not be used.
        try (var ps = admin.prepareStatement("UPDATE equipment_item_details SET details = jsonb_set(jsonb_set("
                + "details, '{attributes}', '{\"Power\":99}'), '{statId}', '161') WHERE item_id = 200")) { ps.executeUpdate(); }
        assertEquals(Map.of("Precision", 53), read().tabs().get(0).equipment().get(1).statAttributes());
        try (var ps = admin.prepareStatement("DELETE FROM equipment_item_stats WHERE stat_id = 162")) { ps.executeUpdate(); }
        assertNull(read().tabs().get(0).equipment().get(1).statAttributes(), "missing itemstats: unresolved, no fixed fallback");
    }
}
