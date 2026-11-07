package web;

import application.CharacterInventoryLayoutService;
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
import repo.CharacterInventoryLayoutRepository;
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
 * STORY-CHAR-006: actual GET /api/account/characters/{name}/inventory HTTP/JSON contract, run
 * through the real controller, service and repository against a disposable Flyway-built schema.
 * Expected values are written out from the seeded rows, not derived from the response DTO.
 */
class CharacterInventoryHttpTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Set<AccountGrant> ALL = EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS,
            AccountGrant.INVENTORIES, AccountGrant.BUILDS);

    private final String schema = "test_inv_http_" + UUID.randomUUID().toString().replace("-", "");
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
        var service = new CharacterInventoryLayoutService(status, new CharacterInventoryLayoutRepository(),
                () -> counting(scoped()), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1));
        mvc = MockMvcBuilders.standaloneSetup(new CharactersApiController(
                new CharacterRosterService(null, status, null, null), null, service)).build();
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

    private void seedLayout() throws SQLException {
        exec("INSERT INTO items (item_id, name, type, rarity, icon_url) VALUES"
                        + " (100, 'Ore', 'Gathering', 'Basic', 'https://render.guildwars2.com/file/aaa/1.png'),"
                        + " (8932, 'Big Bag', 'Bag', 'Basic', 'https://render.guildwars2.com/file/bbb/2.png')",
                "INSERT INTO character_bag_layout_publication (account_id) VALUES ('" + A + "')",
                "INSERT INTO character_bags (account_id, character_id, bag_index, installed, container_item_id, capacity) VALUES"
                        + " ('" + A + "', 1, 0, true, 8932, 3), ('" + A + "', 1, 1, false, NULL, NULL)",
                "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding)"
                        + " VALUES ('" + A + "', 1, 'BAG', 0, 2, 100, 7, 'Account')");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            UUID account, Set<AccountGrant> grants, String path) {
        return get(java.net.URI.create(path)).requestAttr(web.access.RequestAccountContext.ATTRIBUTE,
                new AccountContext(account.toString(), grants, Set.of(), "ctx", Instant.parse("2099-01-01T00:00:00Z")));
    }


    private static final String PATH = "/api/account/characters/Ana%20B/inventory";

    @Test
    void availableLayoutSerializesBagsEmptyCellsQuantityBindingAndBackendImages() throws Exception {
        seedRoster();
        seedLayout();
        sql.clear();
        byte[] body = mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.character").value("Ana B"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.contents").value("HAS_ITEMS"))
                .andExpect(jsonPath("$.bags.length()").value(2))
                .andExpect(jsonPath("$.bags[0].installed").value(true))
                .andExpect(jsonPath("$.bags[0].capacity").value(3))
                .andExpect(jsonPath("$.bags[0].container.name").value("Big Bag"))
                .andExpect(jsonPath("$.bags[0].container.icon").value("/api/items/8932/icon/" + sourceKey("bbb", "2")))
                .andExpect(jsonPath("$.bags[0].slots.length()").value(3))
                .andExpect(jsonPath("$.bags[0].slots[0].empty").value(true))
                .andExpect(jsonPath("$.bags[0].slots[1].empty").value(true))
                .andExpect(jsonPath("$.bags[0].slots[2].empty").value(false))
                .andExpect(jsonPath("$.bags[0].slots[2].quantity").value(7))
                .andExpect(jsonPath("$.bags[0].slots[2].binding").value("Account"))
                .andExpect(jsonPath("$.bags[0].slots[2].item.name").value("Ore"))
                .andExpect(jsonPath("$.bags[1].installed").value(false))
                .andExpect(jsonPath("$.bags[1].slots.length()").value(0))
                .andReturn().getResponse().getContentAsByteArray();
        String json = new String(body, StandardCharsets.UTF_8);
        assertFalse(json.contains("render.guildwars2.com"), json);
        assertTrue(json.contains("\"icon\":\"/api/items/100/"), json);
        // prepared statements: schema guard(s), parent marker, character, publication, manifest, rows, one metadata batch
        System.out.println("[CHAR-006 http] preparedStatements=" + sql.size() + " responseBytes=" + body.length);
        assertTrue(sql.size() <= 8, sql.toString());
        assertEquals(1, sql.stream().filter(q -> q.contains("FROM items")).count(), "one metadata batch");
        assertTrue(sql.stream().noneMatch(q -> q.contains("character_equipment")), "no equipment reads");
    }

    private static String sourceKey(String a, String b) {
        var s = infra.icons.IconSourcePolicy.accept("https://render.guildwars2.com/file/" + a + "/" + b + ".png").orElseThrow();
        return s.sourceKey() + "." + s.extension();
    }

    @Test
    void availableEmptyVersusLegacyMissingManifest() throws Exception {
        seedRoster();
        exec("INSERT INTO character_bag_layout_publication (account_id) VALUES ('" + A + "')");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.contents").value("NO_BAGS")).andExpect(jsonPath("$.bags.length()").value(0));
        exec("DELETE FROM character_bag_layout_publication");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("LAYOUT_REFRESH_REQUIRED"))
                .andExpect(jsonPath("$.bags").doesNotExist());
    }

    @Test
    void forbiddenNotFoundAndStoreFailureStatuses() throws Exception {
        seedRoster();
        exec("INSERT INTO character_bag_layout_publication (account_id) VALUES ('" + A + "')");
        mvc.perform(as(A, EnumSet.of(AccountGrant.ACCOUNT), PATH)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("GRANT_REQUIRED"));
        mvc.perform(as(A, ALL, "/api/account/characters/Nobody/inventory")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.state").value("NOT_FOUND"));
        exec("DROP SCHEMA " + schema + " CASCADE");
        mvc.perform(as(A, ALL, PATH)).andExpect(status().isServiceUnavailable());
        exec("CREATE SCHEMA " + schema);
    }

    @Test
    void sameNamedCharactersOfTwoAccountsNeverMix() throws Exception {
        seedRoster();
        seedLayout();
        exec("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('" + B
                        + "', 'CHARACTERS', 5, '" + NOW.minusSeconds(10) + "')",
                "INSERT INTO character_bag_layout_publication (account_id) VALUES ('" + B + "')",
                "INSERT INTO character_bags (account_id, character_id, bag_index, installed, container_item_id, capacity) VALUES"
                        + " ('" + B + "', 3, 0, true, 8932, 1)");
        mvc.perform(as(B, ALL, PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(5))
                .andExpect(jsonPath("$.contents").value("ALL_SLOTS_EMPTY"))
                .andExpect(jsonPath("$.bags[0].capacity").value(1));
        mvc.perform(as(A, ALL, PATH)).andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.bags[0].capacity").value(3));
    }
}
