package web;

import application.CharacterEquipmentTabsService;
import application.CharacterRosterService;
import application.access.AccountContext;
import application.access.AccountGrant;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.CharacterEquipmentTabsRepository;
import repo.EnvConfig;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STORY-CHAR-005: actual GET /api/account/characters/{name}/equipment-tabs HTTP/JSON contract, run
 * through the real controller, service and repository against a disposable Flyway-built schema.
 * Expected values are written out from the seeded rows, not derived from the response DTO.
 */
class CharacterEquipmentTabsHttpTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Set<AccountGrant> ALL = EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
            AccountGrant.INVENTORIES, AccountGrant.BUILDS);

    private final String schema = "test_equip_http_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean failedRefresh = new AtomicBoolean();
    private final List<String> sql = Collections.synchronizedList(new ArrayList<>());
    private Connection admin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        admin = open();
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        var status = new CharacterRosterService.RefreshStatus() {
            public boolean isRefreshing(AccountScope s, String src) { return false; }
            public boolean lastRefreshFailed(AccountScope s, String src) { return failedRefresh.get(); }
        };
        var service = new CharacterEquipmentTabsService(status, new CharacterEquipmentTabsRepository(),
                () -> counting(scoped()), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1));
        mvc = MockMvcBuilders.standaloneSetup(new CharactersApiController(
                new CharacterRosterService(null, status, null, null), service)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    private Connection counting(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")
                            || method.getName().equals("prepareCall")) {
                        sql.add(args != null && args.length > 0 ? String.valueOf(args[0]) : "<statement>");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }

    private Connection scoped() throws SQLException {
        Connection c = open();
        c.setSchema(schema);
        return c;
    }

    private void exec(String... statements) throws SQLException {
        try (Statement s = admin.createStatement()) {
            for (String q : statements) s.execute(q);
        }
    }

    /** Roster published at generation 2 with Ana (id 1) and Bo (id 2); B owns a same-named Ana. */
    private void seedRoster() throws SQLException {
        exec("INSERT INTO account_registry (account_id) VALUES ('" + A + "'), ('" + B + "')",
                "INSERT INTO characters (account_id, character_id, name, profession, race, gender, level) VALUES"
                        + " ('" + A + "', 1, 'Ana B', 'Guardian', 'Human', 'Female', 80),"
                        + " ('" + A + "', 2, 'Bo', 'Thief', 'Asura', 'Male', 80),"
                        + " ('" + B + "', 3, 'Ana B', 'Warrior', 'Norn', 'Male', 80)",
                "INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                        + " ('" + A + "', 'CHARACTERS', 2, '" + NOW.minusSeconds(60) + "')");
    }

    private void seedEquipment() throws SQLException {
        exec("INSERT INTO items (item_id, name, type, rarity, icon_url) VALUES"
                        + " (100, 'Ascended Helm', 'Armor', 'Ascended', 'https://render.guildwars2.com/file/aaa/1.png'),"
                        + " (200, 'Superior Rune', 'UpgradeComponent', 'Exotic', NULL),"
                        + " (300, 'Infusion', 'UpgradeComponent', 'Ascended', NULL),"
                        + " (400, 'Sword', 'Weapon', 'Legendary', NULL),"
                        + " (500, 'Name Only', NULL, NULL, 'https://render.guildwars2.com/file/bbb/2.png')",
                "INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                        + " ('" + A + "', 'CHARACTER_EQUIPMENT_TABS', 1, '" + NOW.minusSeconds(30) + "')",
                "INSERT INTO character_equipment_coverage (account_id, parent_generation) VALUES ('" + A + "', 2)",
                // Inserted out of order; reads must return in-game (tab number) order: 1, then 3.
                "INSERT INTO character_equipment_tabs (account_id, character_id, tab_number, name, is_active) VALUES"
                        + " ('" + A + "', 1, 3, 'PvE', false), ('" + A + "', 1, 1, NULL, true)",
                "INSERT INTO character_equipment_tab_entries (account_id, character_id, tab_number, entry_order, slot,"
                        + " item_id, location, upgrade_ids, infusion_ids, stat_id, stat_attributes, binding, bound_to) VALUES"
                        + " ('" + A + "', 1, 3, 0, 'Helm', 100, 'Equipped', '{200,999}', '{300}', 1234,"
                        + " '{\"Power\":100,\"Precision\":70}', 'Account', NULL),"
                        + " ('" + A + "', 1, 3, 1, 'WeaponA1', 400, 'Equipped', '{}', '{}', NULL, NULL, NULL, NULL),"
                        + " ('" + A + "', 1, 1, 0, 'Boots', 500, 'Equipped', '{}', '{}', NULL, NULL, NULL, NULL)");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            UUID account, Set<AccountGrant> grants, String path) {
        return get(java.net.URI.create(path)).requestAttr(web.access.RequestAccountContext.ATTRIBUTE,
                new AccountContext(account.toString(), grants, Set.of(), "ctx", Instant.parse("2099-01-01T00:00:00Z")));
    }

    private static final String PATH = "/api/account/characters/Ana%20B/equipment-tabs";

    @Test
    void gatheringGlyphIsReadFromTheSelectedAccountsToolWithItsEffect() throws Exception {
        seedRoster();
        seedEquipment();
        exec("INSERT INTO items (item_id,name,type,rarity) VALUES"
                        + " (100227,'Choya Mining Tool','Gathering','Rare'),"
                        + " (87698,'Glyph of Volatility','UpgradeComponent','Rare')",
                "INSERT INTO equipment_item_details (item_id,details,fetched_at) VALUES"
                        + " (87698,'{\"format\":2,\"itemId\":87698,\"itemType\":\"UpgradeComponent\","
                        + "\"subtype\":\"Default\",\"attributes\":{},\"runeBonuses\":[],"
                        + "\"effects\":[\"Rewards bonus volatile magic while gathering.\"]}',now())",
                "INSERT INTO character_items (account_id,character_id,location,equipment_slot,item_id,upgrade_ids) VALUES"
                        + " ('" + A + "',1,'EQUIPMENT','Pick',100227,'{87698}'),"
                        + " ('" + B + "',3,'EQUIPMENT','Pick',100227,'{999}')");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedEquipment[0].slot").value("Pick"))
                .andExpect(jsonPath("$.sharedEquipment[0].upgrades.length()").value(1))
                .andExpect(jsonPath("$.sharedEquipment[0].upgrades[0].itemId").value(87698))
                .andExpect(jsonPath("$.sharedEquipment[0].upgrades[0].name").value("Glyph of Volatility"))
                .andExpect(jsonPath("$.sharedEquipment[0].upgrades[0].details.effects[0]")
                        .value("Rewards bonus volatile magic while gathering."));
        exec("UPDATE character_items SET upgrade_ids='{}' WHERE account_id='" + A + "'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedEquipment[0].upgrades.length()").value(0));
    }

    @Test
    void newerCharacterDataKeepsStoredEquipmentVisibleUntilItsRefreshFinishes() throws Exception {
        seedRoster();
        seedEquipment();
        exec("UPDATE account_source_state SET generation=3 WHERE account_id='" + A + "' AND source='CHARACTERS'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.reason").value("COVERAGE_INCOMPATIBLE"))
                .andExpect(jsonPath("$.parent.generation").value(3))
                .andExpect(jsonPath("$.coveredParentGeneration").value(2))
                .andExpect(jsonPath("$.tabs.length()").value(2))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.name").value("Ascended Helm"));
        mvc.perform(as(A, ALL, "/api/account/characters/Bo/equipment-tabs"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.tabs").doesNotExist());
        exec("UPDATE character_equipment_coverage SET parent_generation=3 WHERE account_id='" + A + "'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.coveredParentGeneration").value(3))
                .andExpect(jsonPath("$.tabs.length()").value(2));
    }

    @Test
    void availableSelectedCharacterSerializesTabOrderActiveMarkerItemsUpgradesInfusionsAndStats() throws Exception {
        seedRoster();
        seedEquipment();
        byte[] body = mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.character").value("Ana B"))
                .andExpect(jsonPath("$.parent.generation").value(2))
                .andExpect(jsonPath("$.equipment.generation").value(1))
                .andExpect(jsonPath("$.coveredParentGeneration").value(2))
                .andExpect(jsonPath("$.tabs.length()").value(2))
                // in-game (tab number) order: tab 1 before tab 3; active is tab 1 and stays where it is
                .andExpect(jsonPath("$.tabs[1].tab").value(3))
                .andExpect(jsonPath("$.tabs[1].name").value("PvE"))
                .andExpect(jsonPath("$.tabs[1].active").value(false))
                .andExpect(jsonPath("$.tabs[0].tab").value(1))
                .andExpect(jsonPath("$.tabs[0].name").doesNotExist())
                .andExpect(jsonPath("$.tabs[0].active").value(true))
                .andExpect(jsonPath("$.tabs[1].equipment.length()").value(2))
                .andExpect(jsonPath("$.tabs[1].equipment[0].slot").value("Helm"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.itemId").value(100))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.resolved").value(true))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.name").value("Ascended Helm"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.type").value("Armor"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].item.rarity").value("Ascended"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades.length()").value(2))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades[0].name").value("Superior Rune"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades[0].rarity").value("Exotic"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades[1].itemId").value(999))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades[1].resolved").value(false))
                .andExpect(jsonPath("$.tabs[1].equipment[0].upgrades[1].name").doesNotExist())
                .andExpect(jsonPath("$.tabs[1].equipment[0].infusions[0].name").value("Infusion"))
                .andExpect(jsonPath("$.tabs[1].equipment[0].statId").value(1234))
                .andExpect(jsonPath("$.tabs[1].equipment[0].statAttributes.Power").value(100))
                .andExpect(jsonPath("$.tabs[1].equipment[0].statAttributes.Precision").value(70))
                .andExpect(jsonPath("$.tabs[1].equipment[0].binding").value("Account"))
                .andExpect(jsonPath("$.tabs[1].equipment[1].item.rarity").value("Legendary"))
                .andExpect(jsonPath("$.tabs[1].equipment[1].statId").doesNotExist())
                // icon present but type/rarity missing is NOT resolved
                .andExpect(jsonPath("$.tabs[0].equipment[0].item.resolved").value(false))
                .andExpect(jsonPath("$.tabs[0].equipment[0].item.name").value("Name Only"))
                .andExpect(jsonPath("$.tabs[0].equipment[0].item.rarity").doesNotExist())
                .andReturn().getResponse().getContentAsByteArray();
        String json = new String(body, StandardCharsets.UTF_8);
        assertFalse(json.contains("S15") || json.contains("\"Bo\""), "only the requested character is serialized");
        assertFalse(json.contains("apiKey") || json.contains("access_token"));
    }

    @Test
    void selectedDetailFixtureReportsRealBytesStatementCountAndSingleBatchedMetadataLookup() throws Exception {
        seedRoster();
        seedEquipment();
        // Roster-wide data that must not be loaded: Bo (requested character is Ana B) has bulky tabs.
        for (int t = 1; t <= 8; t++) {
            exec("INSERT INTO character_equipment_tabs (account_id, character_id, tab_number, name, is_active)"
                    + " VALUES ('" + A + "', 2, " + t + ", 'Bo" + t + "', " + (t == 1) + ")");
            for (int e = 0; e < 16; e++) {
                exec("INSERT INTO character_equipment_tab_entries (account_id, character_id, tab_number, entry_order,"
                        + " slot, item_id) VALUES ('" + A + "', 2, " + t + ", " + e + ", 'S" + e + "', " + (7000 + e) + ")");
            }
        }
        sql.clear();
        byte[] body = mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        long metadata = sql.stream().filter(q -> q.contains("FROM items")).count();
        long tabRows = sql.stream().filter(q -> q.contains("character_equipment_tab")).count();
        System.out.println("CHAR-EQUIPMENT-DETAIL-REPORT tabs=2 entries=3 responseBytes=" + body.length
                + " preparedStatements=" + sql.size() + " (schema guard + 2 source-marker reads + character lookup"
                + " + coverage + tabs + entries + items) metadataLookups=" + metadata
                + " tabAndEntryReads=" + tabRows + " otherCharacterRowsRead=0 upstreamCalls=0");
        assertEquals(1, metadata, "one batched metadata lookup");
        assertEquals(2, tabRows, "one tab read and one entry read for the selected character only");
        assertFalse(new String(body, StandardCharsets.UTF_8).contains("Bo1"));
        assertTrue(body.length < 5_000);
        // schema guard + parent/equipment marker reads + character + coverage + tabs + entries + items
        // + (STORY-CHAR-014) shared-slot read, batched equipment details and batched itemstats: constant, not per entry
        assertTrue(sql.size() <= 15, "bounded statement count: " + sql.size());
    }

    @Test
    void publishedCoverageWithNoSavedTabsIsAvailableWithEmptyListUnlikeMissingCoverage() throws Exception {
        seedRoster();
        seedEquipment();
        // Bo has no saved tabs although coverage is published: successful empty.
        mvc.perform(as(A, ALL, "/api/account/characters/Bo/equipment-tabs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.tabs.length()").value(0));
        // Remove the coverage: tabs key is absent, never [].
        exec("DELETE FROM character_equipment_coverage", "DELETE FROM account_source_state WHERE source = 'CHARACTER_EQUIPMENT_TABS'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("COVERAGE_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }

    @Test
    void missingBuildsGrantIsForbiddenWithoutTabsAndOtherAccountsSameNameIsNotFound() throws Exception {
        seedRoster();
        seedEquipment();
        mvc.perform(as(A, EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES), PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("GRANT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("EQUIPMENT_GRANT_REQUIRED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
        mvc.perform(as(A, ALL, "/api/account/characters/Nobody/equipment-tabs")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.state").value("NOT_FOUND"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
        // B has a same-named character but no published roster/equipment: A's tabs never leak.
        mvc.perform(as(B, ALL, PATH)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("PARENT_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }

    @Test
    void initialFailedEquipmentCoverageIsServiceUnavailableNotPerpetualPending() throws Exception {
        seedRoster();
        failedRefresh.set(true);
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("REFRESH_FAILED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }
}
