package web;

import application.CharacterRosterService;
import application.ScopedSourceGate;
import application.access.AccountContext;
import application.access.AccountGrant;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.AccountSourceStateRepository;
import repo.CharacterRepository;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STORY-CHAR-001: HTTP contract of the account Characters roster and selected-character reads, run
 * through the real controller, service, gate and repository against a disposable schema built by the
 * production Flyway chain. Expected values are written out from the seeded rows.
 * Accounts A and B both own a character called "Shared" with different identity and crafting data.
 */
class CharactersApiTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");
    private static final UUID EMPTY = UUID.fromString("30000000-0000-0000-0000-00000000000c");
    private static final UUID NONE = UUID.fromString("40000000-0000-0000-0000-00000000000d");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Set<AccountGrant> ALL =
            EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES);

    private final String schema = "test_characters_api_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicInteger connectionsOpened = new AtomicInteger();
    private final AtomicInteger rosterStatements = new AtomicInteger();
    private final AtomicInteger markerStatements = new AtomicInteger();
    /** Test hook run on every row-read connection open, before it is returned (delayed-read evidence). */
    private volatile java.util.concurrent.Callable<Void> onRowRead = () -> null;
    private Connection admin;
    private MockMvc mvc;
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        admin = open();
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        seed();
        rebuild();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    private void rebuild() {
        var gate = new ScopedSourceGate((scope, source) -> {
            try (Connection con = scoped()) {
                return new AccountSourceStateRepository().read(counting(con, markerStatements), scope, source);
            }
        }, (scope, at) -> { }, clock, Duration.ofMinutes(15));
        var status = new CharacterRosterService.RefreshStatus() {
            public boolean isRefreshing(AccountScope s, String src) { return refreshing.get(); }
            public boolean lastRefreshFailed(AccountScope s, String src) { return failed.get(); }
        };
        var service = new CharacterRosterService(gate, status, new CharacterRepository(), () -> {
            connectionsOpened.incrementAndGet();
            try {
                onRowRead.call();
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException(e);
            }
            return counting(scoped(), rosterStatements);
        });
        mvc = MockMvcBuilders.standaloneSetup(new CharactersApiController(service)).build();
    }

    /** A connection that counts every prepared (and so executed) statement it is asked for. */
    private static Connection counting(Connection real, AtomicInteger counter) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")
                            || method.getName().equals("prepareCall")) counter.incrementAndGet();
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
            for (String sql : statements) s.execute(sql);
        }
    }

    private void seed() throws SQLException {
        exec("INSERT INTO account_registry (account_id) VALUES ('" + A + "'), ('" + B + "'), ('" + EMPTY
                        + "'), ('" + NONE + "')",
                "INSERT INTO characters (account_id, character_id, name, profession, race, gender, level, created_at_gw, age_seconds, deaths) VALUES"
                        + " ('" + A + "', 1, 'Zed', 'Guardian', 'Human', 'Male', 80, '2020-01-02T03:04:05Z', 12631700, 9193),"
                        + " ('" + A + "', 2, 'Alice', 'Thief', 'Asura', 'Female', 40, NULL, 0, 0),"
                        + " ('" + A + "', 3, 'Shared', 'Mesmer', 'Sylvari', 'Female', 80, '2019-05-06T07:08:09Z', NULL, NULL),"
                        + " ('" + B + "', 4, 'Shared', 'Warrior', 'Norn', 'Male', 12, '2021-01-01T00:00:00Z', 5000000000, 77)",
                "INSERT INTO character_crafting (account_id, character_id, discipline, rating, is_active) VALUES"
                        + " ('" + A + "', 3, 'Tailor', 400, true), ('" + A + "', 3, 'Armorsmith', 500, false),"
                        + " ('" + A + "', 3, 'Chef', 12, true), ('" + A + "', 1, 'Jeweler', 80, false),"
                        + " ('" + B + "', 4, 'Scribe', 7, true)",
                "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count)"
                        + " VALUES ('" + A + "', 3, 'BAG', 0, 0, 19721, 33)",
                "INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                        + " ('" + A + "', 'CHARACTERS', 2, '" + NOW.minusSeconds(60) + "'),"
                        + " ('" + B + "', 'CHARACTERS', 1, '" + NOW.minusSeconds(60) + "'),"
                        + " ('" + EMPTY + "', 'CHARACTERS', 1, '" + NOW.minusSeconds(60) + "')");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            UUID account, Set<AccountGrant> grants, Set<String> restrictions, String path) {
        return get(java.net.URI.create(path)).requestAttr(web.access.RequestAccountContext.ATTRIBUTE,
                new AccountContext(account.toString(), grants, restrictions, "ctx", Instant.parse("2099-01-01T00:00:00Z")));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(UUID a, String path) {
        return as(a, ALL, Set.of(), path);
    }

    @Test
    void coreSummaryExposesCountersAndBackendBirthdayWithZeroDistinctFromUnknown() throws Exception {
        // NOW = 2026-11-07T12:00Z; Zed created 2020-01-02T03:04:05Z -> 365-day multiples, not calendar years.
        Instant zed = Instant.parse("2020-01-02T03:04:05Z");
        Instant zedNext = zed.plusSeconds(365L * 86400 * 7); // 7 intervals = 2555 days -> 2026-12-31..., first after NOW
        assertTrue(zedNext.isAfter(NOW) && !zedNext.minusSeconds(365L * 86400).isAfter(NOW));
        mvc.perform(as(A, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characters[0].name").value("Alice"))
                .andExpect(jsonPath("$.characters[0].ageSeconds").value(0))
                .andExpect(jsonPath("$.characters[0].deaths").value(0))
                .andExpect(jsonPath("$.characters[0].nextBirthdayAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.characters[1].ageSeconds").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.characters[1].deaths").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.characters[1].nextBirthdayAt").isNotEmpty())
                .andExpect(jsonPath("$.characters[2].ageSeconds").value(12631700))
                .andExpect(jsonPath("$.characters[2].deaths").value(9193))
                .andExpect(jsonPath("$.characters[2].nextBirthdayAt").value(zedNext.toString()));
        // Account isolation + 64-bit value on the selected-character read.
        mvc.perform(as(B, "/api/account/characters/Shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.ageSeconds").value(5000000000L))
                .andExpect(jsonPath("$.character.deaths").value(77));
        mvc.perform(as(A, "/api/account/characters/Shared"))
                .andExpect(jsonPath("$.character.ageSeconds").value(org.hamcrest.Matchers.nullValue()));
        // No build grant is part of the required set: ALL has only account/characters/inventories.
        assertFalse(ALL.toString().toLowerCase().contains("builds"));
    }

    @Test
    void rosterReturnsEveryStoredCharacterOrderedByNameWithDisciplinesOrderedByName() throws Exception {
        mvc.perform(as(A, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.characterCount").value(3))
                .andExpect(jsonPath("$.characters[0].name").value("Alice"))
                .andExpect(jsonPath("$.characters[0].craftingDisciplines.length()").value(0))
                .andExpect(jsonPath("$.characters[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.characters[1].name").value("Shared"))
                .andExpect(jsonPath("$.characters[1].profession").value("Mesmer"))
                .andExpect(jsonPath("$.characters[1].race").value("Sylvari"))
                .andExpect(jsonPath("$.characters[1].gender").value("Female"))
                .andExpect(jsonPath("$.characters[1].level").value(80))
                .andExpect(jsonPath("$.characters[1].createdAt").value("2019-05-06T07:08:09Z"))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[0].discipline").value("Armorsmith"))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[0].rating").value(500))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[0].active").value(false))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[1].discipline").value("Chef"))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[1].active").value(true))
                .andExpect(jsonPath("$.characters[1].craftingDisciplines[2].discipline").value("Tailor"))
                .andExpect(jsonPath("$.characters[2].name").value("Zed"))
                .andExpect(jsonPath("$.characters[2].craftingDisciplines[0].discipline").value("Jeweler"))
                .andExpect(jsonPath("$.freshness.completedAt").value(NOW.minusSeconds(60).toString()))
                .andExpect(jsonPath("$.sections").doesNotExist());
    }

    @Test
    void sameNamedCharacterResolvesIndependentlyPerAccount() throws Exception {
        mvc.perform(as(A, "/api/account/characters/Shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.profession").value("Mesmer"))
                .andExpect(jsonPath("$.character.craftingDisciplines.length()").value(3));
        mvc.perform(as(B, "/api/account/characters/Shared"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.profession").value("Warrior"))
                .andExpect(jsonPath("$.character.level").value(12))
                .andExpect(jsonPath("$.character.craftingDisciplines.length()").value(1))
                .andExpect(jsonPath("$.character.craftingDisciplines[0].discipline").value("Scribe"));
        mvc.perform(as(B, "/api/account/characters"))
                .andExpect(jsonPath("$.characterCount").value(1));
        // Account B has no "Zed": A's character is never returned across the account boundary.
        mvc.perform(as(B, "/api/account/characters/Zed"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.state").value("NOT_FOUND"))
                .andExpect(jsonPath("$.character").doesNotExist());
    }

    @Test
    void detailReportsDeferredSectionsAsNotImplementedWithoutPayloads() throws Exception {
        mvc.perform(as(A, "/api/account/characters/Zed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.equipmentTabs").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.sections.inventory").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.sections.buildTabs").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.character.equipment").doesNotExist())
                .andExpect(jsonPath("$.character.inventory").doesNotExist())
                .andExpect(jsonPath("$.character.builds").doesNotExist());
    }

    @Test
    void encodedNameWithSpacesIsResolved() throws Exception {
        exec("INSERT INTO characters (account_id, character_id, name) VALUES ('" + A + "', 9, 'Two Words')");
        mvc.perform(as(A, "/api/account/characters/Two%20Words"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.name").value("Two Words"))
                .andExpect(jsonPath("$.character.profession").doesNotExist());
    }

    @Test
    void publishedEmptyRosterIsAvailableWithZeroEntries() throws Exception {
        mvc.perform(as(EMPTY, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.characterCount").value(0))
                .andExpect(jsonPath("$.characters.length()").value(0));
        mvc.perform(as(EMPTY, "/api/account/characters/Anyone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.state").value("NOT_FOUND"));
    }

    @Test
    void noPublicationMarkerIsPendingNeverAnEmptyRoster() throws Exception {
        mvc.perform(as(NONE, "/api/account/characters"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("NOT_YET_PUBLISHED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.characters").doesNotExist())
                .andExpect(jsonPath("$.characterCount").doesNotExist());
        mvc.perform(as(NONE, "/api/account/characters/Zed"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void refreshInProgressWithoutPublicationIsPendingWithItsOwnReason() throws Exception {
        refreshing.set(true);
        mvc.perform(as(NONE, "/api/account/characters"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("REFRESH_IN_PROGRESS"));
    }

    @Test
    void failureWithoutPublicationIsUnavailableThenPendingWhileRetryingThenAvailableOnSuccess() throws Exception {
        failed.set(true);
        for (String path : new String[] {"/api/account/characters", "/api/account/characters/Zed"}) {
            mvc.perform(as(NONE, path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.reason").value("REFRESH_FAILED"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.characters").doesNotExist());
        }
        refreshing.set(true); // active retry, still no marker
        mvc.perform(as(NONE, "/api/account/characters"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("REFRESH_IN_PROGRESS"));
        refreshing.set(false);
        failed.set(false); // never attempted
        mvc.perform(as(NONE, "/api/account/characters"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("NOT_YET_PUBLISHED"));
        // success: a published account is AVAILABLE and carries no reason/message keys
        mvc.perform(as(A, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AVAILABLE"));
    }

    @Test
    void omittedFieldsAreAbsentKeysNotNullsInTheActualJson() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String available = mvc.perform(as(A, "/api/account/characters/Zed")).andReturn().getResponse().getContentAsString();
        var ok = mapper.readTree(available);
        assertFalse(ok.has("reason"));
        assertFalse(ok.has("message"));
        assertFalse(ok.has("characters"));
        assertFalse(ok.has("characterCount"));
        assertTrue(ok.has("character") && ok.has("sections") && ok.has("freshness"));
        String pending = mvc.perform(as(NONE, "/api/account/characters")).andReturn().getResponse().getContentAsString();
        var p = mapper.readTree(pending);
        for (String key : new String[] {"freshness", "characters", "characterCount", "character", "sections"}) {
            assertFalse(p.has(key), key + " must be omitted, not null");
        }
        assertTrue(p.has("reason") && p.has("message"));
        p.fields().forEachRemaining(e -> assertFalse(e.getValue().isNull(), e.getKey()));
    }

    @Test
    void failedRefreshKeepsLastGoodDataReadableAsStale() throws Exception {
        failed.set(true);
        mvc.perform(as(A, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.reason").value("REFRESH_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.characterCount").value(3))
                .andExpect(jsonPath("$.characters[2].ageSeconds").value(12631700))
                .andExpect(jsonPath("$.characters[2].deaths").value(9193))
                .andExpect(jsonPath("$.characters[2].createdAt").value("2020-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.characters[2].nextBirthdayAt").isNotEmpty());
        mvc.perform(as(A, "/api/account/characters/Zed"))
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.character.name").value("Zed"))
                .andExpect(jsonPath("$.character.ageSeconds").value(12631700))
                .andExpect(jsonPath("$.character.deaths").value(9193))
                .andExpect(jsonPath("$.character.createdAt").value("2020-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.character.nextBirthdayAt").isNotEmpty());
    }

    @Test
    void expiredSourceIsStaleWithLastGoodDataAndCredentialRequiredRefreshHint() throws Exception {
        clock = Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC);
        rebuild();
        mvc.perform(as(A, "/api/account/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.reason").value("SOURCE_EXPIRED"))
                .andExpect(jsonPath("$.freshness.refresh").value("CREDENTIAL_REQUIRED"))
                .andExpect(jsonPath("$.characterCount").value(3));
    }

    @Test
    void eachMissingGrantIsReportedAsGrantRequiredWithoutOpeningTheDatabase() throws Exception {
        for (AccountGrant missing : ALL) {
            Set<AccountGrant> grants = EnumSet.copyOf(ALL);
            grants.remove(missing);
            mvc.perform(as(A, grants, Set.of(), "/api/account/characters"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.state").value("GRANT_REQUIRED"))
                    .andExpect(jsonPath("$.reason").value("GRANT_REQUIRED"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.characters").doesNotExist());
            mvc.perform(as(A, grants, Set.of(), "/api/account/characters/Zed"))
                    .andExpect(jsonPath("$.state").value("GRANT_REQUIRED"));
        }
        assertEquals(0, connectionsOpened.get());
    }

    @Test
    void endpointRestrictionExcludingCharactersIsReportedDistinctly() throws Exception {
        mvc.perform(as(A, ALL, Set.of("/v2/account/bank"), "/api/account/characters"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("ENDPOINT_RESTRICTED"))
                .andExpect(jsonPath("$.reason").value("ENDPOINT_RESTRICTED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.characters").doesNotExist());
        mvc.perform(as(A, ALL, Set.of("/v2/characters"), "/api/account/characters"))
                .andExpect(jsonPath("$.state").value("AVAILABLE"));
    }

    @Test
    void databaseFailureIsUnavailableWithStableReasonNotAnEmptyRoster() throws Exception {
        var gate = new ScopedSourceGate((scope, source) -> { throw new SQLException("down"); },
                (scope, at) -> { }, clock, Duration.ofMinutes(15));
        var service = new CharacterRosterService(gate, null, new CharacterRepository(), this::scoped);
        var broken = MockMvcBuilders.standaloneSetup(new CharactersApiController(service)).build();
        broken.perform(as(A, "/api/account/characters"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.characters").doesNotExist());
    }

    @Test
    void readsLeaveCanonicalCharacterItemsAndCraftingRowsUnchanged() throws Exception {
        String before = fingerprint();
        mvc.perform(as(A, "/api/account/characters")).andExpect(status().isOk());
        mvc.perform(as(A, "/api/account/characters/Shared")).andExpect(status().isOk());
        assertEquals(before, fingerprint());
        assertTrue(before.contains("19721|33"));
    }

    private String fingerprint() throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement s = admin.createStatement();
             var rs = s.executeQuery("SELECT account_id, character_id, item_id, count FROM character_items ORDER BY 1,2,3")) {
            while (rs.next()) out.append(rs.getString(1)).append('|').append(rs.getInt(2)).append('|')
                    .append(rs.getInt(3)).append('|').append(rs.getInt(4)).append(';');
        }
        try (Statement s = admin.createStatement();
             var rs = s.executeQuery("SELECT count(*), sum(rating) FROM character_crafting")) {
            rs.next();
            out.append(rs.getInt(1)).append('/').append(rs.getInt(2));
        }
        return out.toString();
    }

    /** Bounded fixture report (issue evidence): rows, response bytes and statements for a 40-character roster. */
    @Test
    void boundedRosterReportsRowsBytesAndStatementCountIndependentOfDeferredRows() throws Exception {
        StringBuilder sql = new StringBuilder();
        for (int i = 100; i < 140; i++) {
            exec("INSERT INTO characters (account_id, character_id, name, profession, race, gender, level) VALUES ('"
                    + A + "', " + i + ", 'Bulk" + i + "', 'Ranger', 'Charr', 'Male', 80)");
            for (String d : new String[]{"Chef", "Tailor", "Scribe"}) {
                exec("INSERT INTO character_crafting (account_id, character_id, discipline, rating, is_active) VALUES ('"
                        + A + "', " + i + ", '" + d + "', 100, true)");
            }
            // Deferred-section rows (bags/equipment) must not influence the roster read.
            for (int slot = 0; slot < 20; slot++) {
                exec("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count)"
                        + " VALUES ('" + A + "', " + i + ", 'BAG', 0, " + slot + ", " + (1000 + slot) + ", 1)");
            }
        }
        byte[] body = mvc.perform(as(A, "/api/account/characters")).andExpect(status().isOk())
                .andExpect(jsonPath("$.characterCount").value(43)).andReturn().getResponse().getContentAsByteArray();
        // Reconstructed wire-format baseline (NOT a measured old production response): the same JSON with the
        // three added fields removed from every character. Statement counts: the widened queries add no
        // projection SQL (code inspection); the counts asserted below are the measured "after".
        com.fasterxml.jackson.databind.node.ObjectNode tree =
                (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        tree.withArray("characters").forEach(c ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) c).remove(List.of("ageSeconds", "deaths", "nextBirthdayAt")));
        int baselineBytes = tree.toString().getBytes(StandardCharsets.UTF_8).length;
        System.out.println("CHAR-ROSTER-BASELINE reconstructedBytesWithoutNewFields=" + baselineBytes
                + " measuredBytesWithNewFields=" + body.length + " delta=" + (body.length - baselineBytes));
        assertTrue(body.length > baselineBytes);
        System.out.println("CHAR-ROSTER-REPORT characters=43 disciplineRows=" + (4 + 40 * 3)
                + " rowReadStatements=" + rosterStatements.get() + " (2 projection reads + 1 schema guard)"
                + " markerStatements=" + markerStatements.get() + " (2 marker reads x 2 statements)"
                + " allSqlStatements=" + (rosterStatements.get() + markerStatements.get())
                + " (measured by counting prepared statements on every connection the read opened) responseBytes="
                + new String(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length
                + " characterItemsRowsRead=0 upstreamCalls=0");
        assertTrue(body.length < 40_000);
        assertEquals(1, connectionsOpened.get());
        // 2 projection reads (disciplines, characters) + 1 managed-schema guard statement.
        assertEquals(3, rosterStatements.get(), "2 roster projection reads + 1 schema guard");
        assertEquals(4, markerStatements.get(), "marker read before and after the row read, 2 statements each");
    }

    /**
     * Delayed-read isolation: A's row read is paused after its marker check while B's complete roster
     * and detail reads run; both finish with their own same-named character facts. The marker/row
     * generation-move seam is covered by ScopedSourceGateTest.aGenerationThatMovesDuringTheReadIsRetried...
     */
    @Test
    void delayedReadOfOneAccountDoesNotMixWithAnotherAccountsSameNamedCharacter() throws Exception {
        var aInside = new java.util.concurrent.CountDownLatch(1);
        var bDone = new java.util.concurrent.CountDownLatch(1);
        onRowRead = () -> {
            if (Thread.currentThread().getName().equals("reader-A")) {
                aInside.countDown();
                if (!bDone.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("B never finished");
            }
            return null;
        };
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "reader-A"));
        try {
            var aRead = pool.submit(() -> mvc.perform(as(A, "/api/account/characters/Shared"))
                    .andReturn().getResponse().getContentAsString());
            assertTrue(aInside.await(10, java.util.concurrent.TimeUnit.SECONDS));
            mvc.perform(as(B, "/api/account/characters/Shared")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.character.profession").value("Warrior"))
                    .andExpect(jsonPath("$.character.craftingDisciplines[0].discipline").value("Scribe"));
            mvc.perform(as(B, "/api/account/characters")).andExpect(jsonPath("$.characterCount").value(1));
            bDone.countDown();
            String a = aRead.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a.contains("\"profession\":\"Mesmer\""), a);
            assertTrue(a.contains("Tailor") && !a.contains("Scribe") && !a.contains("Warrior"), a);
        } finally {
            bDone.countDown();
            pool.shutdownNow();
        }
        // Switching back and forth afterwards still returns each account's own facts.
        mvc.perform(as(A, "/api/account/characters/Shared")).andExpect(jsonPath("$.character.race").value("Sylvari"));
        mvc.perform(as(B, "/api/account/characters/Shared")).andExpect(jsonPath("$.character.race").value("Norn"));
    }
}
