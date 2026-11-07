package web;

import application.CharacterBuildTabsService;
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
import repo.CharacterBuildTabsRepository;
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
 * STORY-CHAR-007: actual GET /api/account/characters/{name}/build-tabs HTTP/JSON contract, run
 * through the real controller, service and repository against a disposable Flyway-built schema.
 * Expected values are written out from the seeded rows, not derived from the response DTO.
 */
class CharacterBuildTabsHttpTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Set<AccountGrant> ALL = EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
            AccountGrant.INVENTORIES, AccountGrant.BUILDS);

    private final String schema = "test_build_http_" + UUID.randomUUID().toString().replace("-", "");
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
        var service = new CharacterBuildTabsService(status, new CharacterBuildTabsRepository(),
                () -> counting(scoped()), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1));
        mvc = MockMvcBuilders.standaloneSetup(new CharactersApiController(
                new CharacterRosterService(null, status, null, null), null, null, service)).build();
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

    private void seedBuilds() throws SQLException {
        exec("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                        + " ('" + A + "', 'CHARACTER_BUILD_TABS', 1, '" + NOW.minusSeconds(30) + "')",
                "INSERT INTO character_build_coverage (account_id, parent_generation) VALUES ('" + A + "', 2)",
                // Inserted out of order; reads return in-game (tab number) order: 1, then 3.
                "INSERT INTO character_build_tabs (account_id, character_id, tab_number, name, profession, is_active,"
                        + " land_heal, land_elite, land_utilities, aquatic_heal, aquatic_elite, aquatic_utilities,"
                        + " land_legends, aquatic_legends, land_pets, aquatic_pets) VALUES"
                        + " ('" + A + "', 1, 3, 'PvE', 'Revenant', false, NULL, 9, '{5,NULL,7}', 4, NULL, '{NULL,NULL,NULL}',"
                        + "  '{Legend1,NULL}', '{NULL,Legend4}', NULL, NULL),"
                        + " ('" + A + "', 1, 1, NULL, 'Ranger', true, 1, 2, '{3,4,5}', NULL, NULL, '{6,NULL,8}',"
                        + "  NULL, NULL, '{42,NULL}', '{NULL,NULL}')",
                "INSERT INTO character_build_tab_specializations (account_id, character_id, tab_number, position,"
                        + " specialization_id, traits) VALUES"
                        + " ('" + A + "', 1, 3, 1, 15, '{101,NULL,103}'), ('" + A + "', 1, 3, 2, NULL, '{NULL,NULL,NULL}'),"
                        + " ('" + A + "', 1, 3, 3, 17, '{NULL,NULL,NULL}'),"
                        + " ('" + A + "', 1, 1, 1, 30, '{1,2,3}'), ('" + A + "', 1, 1, 2, 31, '{4,5,6}'),"
                        + " ('" + A + "', 1, 1, 3, 32, '{7,8,9}')");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            UUID account, Set<AccountGrant> grants, String path) {
        return get(java.net.URI.create(path)).requestAttr(web.access.RequestAccountContext.ATTRIBUTE,
                new AccountContext(account.toString(), grants, Set.of(), "ctx", Instant.parse("2099-01-01T00:00:00Z")));
    }

    private static final String PATH = "/api/account/characters/Ana%20B/build-tabs";

    @Test
    void availableSelectedCharacterSerializesOrderNullsAndUnresolvedReferences() throws Exception {
        seedRoster();
        seedBuilds();
        byte[] body = mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.character").value("Ana B"))
                .andExpect(jsonPath("$.parent.generation").value(2))
                .andExpect(jsonPath("$.builds.generation").value(1))
                .andExpect(jsonPath("$.coveredParentGeneration").value(2))
                .andExpect(jsonPath("$.tabs.length()").value(2))
                .andExpect(jsonPath("$.tabs[0].tab").value(1))
                .andExpect(jsonPath("$.tabs[0].active").value(true))
                .andExpect(jsonPath("$.tabs[0].name").doesNotExist())
                .andExpect(jsonPath("$.tabs[0].profession").value("Ranger"))
                .andExpect(jsonPath("$.tabs[0].specializations.length()").value(3))
                .andExpect(jsonPath("$.tabs[0].specializations[1].specialization.id").value(31))
                .andExpect(jsonPath("$.tabs[0].specializations[1].specialization.resolved").value(false))
                .andExpect(jsonPath("$.tabs[0].specializations[1].traits[2].id").value(6))
                .andExpect(jsonPath("$.tabs[0].skills.heal.id").value(1))
                .andExpect(jsonPath("$.tabs[0].skills.heal.resolved").value(false))
                .andExpect(jsonPath("$.tabs[0].skills.utilities.length()").value(3))
                .andExpect(jsonPath("$.tabs[0].aquaticSkills.heal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[0].aquaticSkills.utilities[1]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[0].aquaticSkills.utilities[2].id").value(8))
                .andExpect(jsonPath("$.tabs[0].pets.length()").value(2))
                .andExpect(jsonPath("$.tabs[0].pets[0].id").value(42))
                .andExpect(jsonPath("$.tabs[0].pets[1]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[0].aquaticPets[0]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[0].legends").doesNotExist())
                .andExpect(jsonPath("$.tabs[1].tab").value(3))
                .andExpect(jsonPath("$.tabs[1].name").value("PvE"))
                .andExpect(jsonPath("$.tabs[1].active").value(false))
                .andExpect(jsonPath("$.tabs[1].specializations[1].specialization").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[1].specializations[1].traits.length()").value(3))
                .andExpect(jsonPath("$.tabs[1].specializations[1].traits[0]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[1].specializations[0].traits[1]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[1].specializations[0].traits[2].id").value(103))
                .andExpect(jsonPath("$.tabs[1].legends[0].id").value("Legend1"))
                .andExpect(jsonPath("$.tabs[1].legends[0].resolved").value(false))
                .andExpect(jsonPath("$.tabs[1].legends[1]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tabs[1].aquaticLegends[1].id").value("Legend4"))
                .andExpect(jsonPath("$.tabs[1].pets").doesNotExist())
                .andReturn().getResponse().getContentAsByteArray();
        String json = new String(body, StandardCharsets.UTF_8);
        assertFalse(json.contains("\"Bo\"") || json.contains("apiKey") || json.contains("access_token"));
        assertFalse(json.contains("icon"), "no names or icons are fabricated");
    }

    @Test
    void selectedReadReportsBytesAndBoundedStatementsWithoutOtherCharactersOrItems() throws Exception {
        seedRoster();
        seedBuilds();
        exec("INSERT INTO character_build_tabs (account_id, character_id, tab_number, name, profession, is_active,"
                + " land_utilities, aquatic_utilities) VALUES ('" + A + "', 2, 1, 'BoTab', 'Thief', true, '{NULL,NULL,NULL}', '{NULL,NULL,NULL}')");
        sql.clear();
        byte[] body = mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        System.out.println("CHAR-BUILD-DETAIL-REPORT tabs=2 responseBytes=" + body.length
                + " preparedStatements=" + sql.size() + " upstreamCalls=0");
        assertFalse(new String(body, StandardCharsets.UTF_8).contains("BoTab"));
        assertTrue(sql.stream().noneMatch(q -> q.contains("FROM items") || q.contains("character_equipment")
                || q.contains("character_items")), "builds read touches no items/equipment/inventory");
        assertTrue(sql.stream().filter(q -> q.contains("character_build_tab")).count() == 2,
                "one tab read and one specialization read");
        assertTrue(body.length < 5_000);
        // Original read budget (10) plus at most one batched metadata statement per namespace (5), never per reference.
        assertTrue(sql.size() <= 15, "bounded statement count: " + sql.size());
    }

    @Test
    void publishedCoverageWithNoSavedTabsIsEmptyListUnlikeMissingCoverage() throws Exception {
        seedRoster();
        seedBuilds();
        mvc.perform(as(A, ALL, "/api/account/characters/Bo/build-tabs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.tabs.length()").value(0));
        exec("DELETE FROM character_build_coverage", "DELETE FROM account_source_state WHERE source = 'CHARACTER_BUILD_TABS'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("COVERAGE_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }

    @Test
    void incompatibleCoverageIsPendingAndStaleLastGoodIsServedAfterFailure() throws Exception {
        seedRoster();
        seedBuilds();
        failedRefresh.set(true);
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.reason").value("BUILD_TABS_REFRESH_FAILED"))
                .andExpect(jsonPath("$.tabs.length()").value(2));
        failedRefresh.set(false);
        exec("UPDATE account_source_state SET generation = 3 WHERE source = 'CHARACTERS'");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("COVERAGE_INCOMPATIBLE"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }

    @Test
    void grantEndpointRestrictionNotFoundAndOtherAccountStatesAreDistinct() throws Exception {
        seedRoster();
        seedBuilds();
        mvc.perform(as(A, EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES), PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("GRANT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("BUILD_TABS_GRANT_REQUIRED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
        mvc.perform(as(A, ALL, "/api/account/characters/Nobody/build-tabs")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.state").value("NOT_FOUND"));
        mvc.perform(as(B, ALL, PATH)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("PARENT_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }

    @Test
    void initialFailedCoverageIsServiceUnavailableNotPerpetualPending() throws Exception {
        seedRoster();
        failedRefresh.set(true);
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("REFRESH_FAILED"))
                .andExpect(jsonPath("$.tabs").doesNotExist());
    }
}
