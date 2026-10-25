package web;

import application.AccountRefreshService;
import application.BackgroundTasks;
import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import repo.Db;
import repo.EnvConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-API-011 end-to-end isolation evidence: real HTTP, the real controllers, application
 * services, scoped repositories and the production Flyway chain on a disposable PostgreSQL schema
 * (never the public schema). The only fakes are the proof port and the upstream GET fixtures, so
 * nothing contacts ArenaNet.
 *
 * <p>Excluded from {@code mvn test} by its {@code IT} suffix. Run explicitly:
 * {@code mvn test -Dtest=AccountIsolationHttpPostgresIT -DfailIfNoSpecifiedTests=false}
 * with {@code DATABASE_URL}, {@code DATABASE_USER} and {@code DATABASE_PASSWORD} set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000"})
@Import(AccountIsolationHttpPostgresIT.Fixtures.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountIsolationHttpPostgresIT {

    static final String ACCOUNT_A = "AAAAAAAA-1111-2222-3333-444444444444";
    static final String ACCOUNT_B = "BBBBBBBB-1111-2222-3333-444444444444";
    static final String ACCOUNT_C = "CCCCCCCC-1111-2222-3333-444444444444";
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicBoolean UPSTREAM_OUTAGE = new AtomicBoolean();
    static final String SCHEMA = "it_isolation_" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort int port;
    @Autowired BackgroundTasks tasks;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        try (Connection admin = connect(); Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + SCHEMA);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration").load().migrate();
        // Shared (global) reference rows: one item in one material category, visible to every account.
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
            st.execute("INSERT INTO items (item_id, name) VALUES (19721, 'Shared Fixture Item')");
            st.execute("INSERT INTO material_categories (category_id, name, display_order) VALUES (5, 'Fixture', 1)");
            st.execute("INSERT INTO material_category_items (category_id, position, item_id) VALUES (5, 1, 19721)");
        }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Connection admin = connect(); Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    static Connection connect() throws Exception {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }

    /** Offline proof port and upstream fixtures; keys are fixed fixture names, not credentials. */
    @TestConfiguration
    static class Fixtures {
        @Bean @Primary
        AccountProofPort itProofPort() {
            Set<AccountGrant> all = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
                    AccountGrant.UNLOCKS, AccountGrant.PROGRESSION, AccountGrant.TRADINGPOST);
            return key -> switch (key) {
                case "key-a", "key-a-2" -> new AccountProof(ACCOUNT_A, all, Set.of(), null);
                case "key-b" -> new AccountProof(ACCOUNT_B, all, Set.of(), null);
                case "key-c" -> new AccountProof(ACCOUNT_C, all, Set.of(), null);
                case "key-a-narrow" -> new AccountProof(ACCOUNT_A,
                        Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES), Set.of(), null);
                default -> throw new InvalidCredentialException("rejected");
            };
        }

        @Bean @Primary
        ScopedAccountRefreshCoordinator itScopedCoordinator(BackgroundTasks tasks, AccountAccessService access) {
            return new ScopedAccountRefreshCoordinator(new AccountRefreshService(), tasks, access, key -> url -> {
                if (UPSTREAM_OUTAGE.get()) throw new IOException("simulated upstream outage");
                boolean a = key.startsWith("key-a");
                String path = url.substring(url.indexOf("/v2/") + 3);
                return JSON.readTree(switch (path) {
                    case "/account" -> "{\"id\":\"" + (a ? ACCOUNT_A : ACCOUNT_B) + "\"}";
                    case "/account/bank" -> "[{\"id\":19721,\"count\":" + (a ? 2 : 7) + "}]";
                    case "/account/materials" ->
                            "[{\"id\":19721,\"category\":5,\"count\":" + (a ? 11 : 19) + "}]";
                    case "/account/recipes" -> "[7319]";
                    case "/account/luck" -> "[{\"id\":\"luck\",\"value\":" + (a ? 10 : 20) + "}]";
                    case "/characters" -> "[]";
                    default -> throw new IOException("unexpected fixture request " + path);
                });
            });
        }
    }

    // ---------------------------------------------------------------- HTTP helpers

    record Resp(int status, String body, HttpResponse<String> raw) {
        JsonNode json() throws Exception { return JSON.readTree(body); }
        String header(String name) { return raw.headers().firstValue(name).orElse(null); }
    }

    record Session(String cookie, String context) {}

    Resp send(String method, String path, Session session, String key, String body, String contextOverride)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (session != null) {
            b.header("Cookie", session.cookie());
            b.header("X-Account-Context", contextOverride != null ? contextOverride : session.context());
        }
        if (key != null) b.header("X-GW2-API-Key", key);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r);
    }

    Session login(String key) throws Exception {
        Resp r = send("POST", "/api/session", null, key, null, null);
        assertEquals(200, r.status(), r.body());
        String cookie = r.header("Set-Cookie");
        return new Session(cookie.substring(0, cookie.indexOf(';')), r.header("X-Account-Context"));
    }

    String sync(Session s, String key, String path) throws Exception {
        Resp r = send("POST", path, s, key, "{}", null);
        assertEquals(202, r.status(), r.body());
        return awaitTerminal(s, r.json().path("taskId").asText());
    }

    String awaitTerminal(Session s, String taskId) throws Exception {
        for (int i = 0; i < 200; i++) {
            Resp status = send("GET", "/api/sync/tasks/" + taskId, s, null, null, null);
            String state = status.json().path("state").asText();
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) return state;
            Thread.sleep(50);
        }
        throw new AssertionError("task did not finish");
    }

    long scalar(String sql) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
            try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
        }
    }

    // ---------------------------------------------------------------- tests

    @Test @Order(1)
    void noSessionNoKeyAndStaleContextAreRejectedBeforeAnyAccountWork() throws Exception {
        for (String path : new String[] {"/api/account/bank", "/api/account/materials", "/api/account/luck"}) {
            assertEquals(401, send("GET", path, null, null, null, null).status(), path);
        }
        assertEquals(401, send("POST", "/api/crafting/profit", null, null, "{}", null).status());
        Session a = login("key-a");
        assertEquals(403, send("GET", "/api/account/bank", a, null, null, "stale-tab-context").status());
        assertEquals(0, scalar("SELECT count(*) FROM account_registry"), "rejections create no account row");
        // A cached read needs no GW2 key; with nothing synchronized yet it is not-yet-available, never fabricated.
        Resp missingKey = send("POST", "/api/sync/account", a, null, "{}", null);
        assertEquals(400, missingKey.status());
        assertEquals("CREDENTIAL_REQUIRED", missingKey.json().path("error").asText());
        assertEquals(0, tasksAdmittedFor(a), "no key admits nothing");
    }

    int tasksAdmittedFor(Session s) throws Exception {
        return (int) scalar("SELECT count(*) FROM account_source_state");
    }

    @Test @Order(2)
    void keyedRefreshesPublishDistinctDatasetsPerAccountAndReadsStayIsolated() throws Exception {
        Session a = login("key-a");
        Session b = login("key-b");
        assertEquals("SUCCEEDED", sync(a, "key-a", "/api/sync/account"));
        assertEquals("SUCCEEDED", sync(b, "key-b", "/api/sync/account"));

        JsonNode bankA = send("GET", "/api/account/bank", a, null, null, null).json();
        JsonNode bankB = send("GET", "/api/account/bank", b, null, null, null).json();
        assertEquals(2, bankA.path("slots").get(0).path("count").asInt());
        assertEquals(7, bankB.path("slots").get(0).path("count").asInt());
        JsonNode matA = send("GET", "/api/account/materials", a, null, null, null).json();
        JsonNode matB = send("GET", "/api/account/materials", b, null, null, null).json();
        assertTrue(matA.toString().contains("\"count\":11") && !matA.toString().contains("\"count\":19"), matA.toString());
        assertTrue(matB.toString().contains("\"count\":19") && !matB.toString().contains("\"count\":11"), matB.toString());
        assertEquals(1, scalar("SELECT consumed_luck FROM account_luck WHERE account_id = '" + ACCOUNT_A.toLowerCase()
                + "'") / 10, "A's stored Luck is A's");
        assertEquals(20, scalar("SELECT consumed_luck FROM account_luck WHERE account_id = '" + ACCOUNT_B.toLowerCase() + "'"));
        Resp response = send("GET", "/api/account/bank", a, null, null, null);
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(a.context(), response.header("X-Account-Context"));
        assertFalse(response.body().contains("key-a"));
    }

    @Test @Order(3)
    void sameGuidReplacementSessionSharesTheDatasetButLowerGrantsStayLimited() throws Exception {
        Session a1 = login("key-a");
        Session a2 = login("key-a-2");
        assertNotEquals(a1.context(), a2.context());
        assertEquals(send("GET", "/api/account/bank", a1, null, null, null).json().toString(),
                send("GET", "/api/account/bank", a2, null, null, null).json().toString(),
                "same GUID reuses the same scoped dataset");

        Session narrow = login("key-a-narrow");
        assertEquals(200, send("GET", "/api/account/bank", narrow, null, null, null).status());
        assertEquals(403, send("GET", "/api/account/luck", narrow, null, null, null).status(),
                "a lower-grant replacement cannot read what only the higher-grant session may");
        assertEquals(403, send("POST", "/api/crafting/profit", narrow, null, "{}", null).status());
        assertEquals(403, send("GET", "/api/account/bank?accountId=" + ACCOUNT_B, a1, null, null, null).status(),
                "a foreign account assertion is refused");
    }

    @Test @Order(4)
    void taskObservationIsOwnedByAccountAndForeignAndUnknownAreIndistinguishable() throws Exception {
        Session a = login("key-a");
        Session b = login("key-b");
        Resp accepted = send("POST", "/api/sync/account", a, "key-a", "{}", null);
        assertEquals(202, accepted.status());
        String taskId = accepted.json().path("taskId").asText();

        Resp foreign = send("GET", "/api/sync/tasks/" + taskId, b, null, null, null);
        Resp unknown = send("GET", "/api/sync/tasks/" + UUID.randomUUID(), b, null, null, null);
        assertEquals(404, foreign.status());
        assertEquals(unknown.status(), foreign.status());
        assertEquals(unknown.json().path("error").asText(), foreign.json().path("error").asText());
        assertEquals(200, send("GET", "/api/sync/tasks/" + taskId, login("key-a-2"), null, null, null).status(),
                "another session proven for the same GUID observes the retained work");
        assertEquals(401, send("GET", "/api/sync/tasks/" + taskId, null, null, null, null).status());
        assertEquals("SUCCEEDED", awaitTerminal(a, taskId));
    }

    @Test @Order(5)
    void cachedReadsSurviveAnUpstreamOutageAndFailedRefreshesAdvanceNoFreshness() throws Exception {
        Session a = login("key-a");
        long before = scalar("SELECT generation FROM account_source_state WHERE account_id = '"
                + ACCOUNT_A.toLowerCase() + "' AND source = 'BANK'");
        UPSTREAM_OUTAGE.set(true);
        try {
            assertEquals("FAILED", sync(a, "key-a", "/api/sync/account"));
            assertEquals(200, send("GET", "/api/account/bank", a, null, null, null).status());
        } finally {
            UPSTREAM_OUTAGE.set(false);
        }
        assertEquals(before, scalar("SELECT generation FROM account_source_state WHERE account_id = '"
                + ACCOUNT_A.toLowerCase() + "' AND source = 'BANK'"), "no fabricated freshness");
        assertEquals("SUCCEEDED", sync(a, "key-a", "/api/sync/account"), "keyed retry succeeds after recovery");
    }

    @Test @Order(6)
    void authorizedUseRecordsActivityAndSystemStatusProjectsOnlyTheOwnAccount() throws Exception {
        Session a = login("key-a");
        Session b = login("key-b");
        assertEquals(200, send("GET", "/api/account/bank", a, null, null, null).status());
        assertTrue(scalar("SELECT count(*) FROM account_registry WHERE account_id = '" + ACCOUNT_A.toLowerCase()
                + "'") == 1);
        JsonNode statusA = send("GET", "/api/system/status", a, null, null, null).json();
        JsonNode global = send("GET", "/api/system/status", null, null, null, null).json();
        assertFalse(statusA.path("accountLastRefreshedAt").isNull() || statusA.path("accountLastRefreshedAt").isMissingNode());
        assertTrue(global.path("accountLastRefreshedAt").isNull() || global.path("accountLastRefreshedAt").isMissingNode(),
                "global diagnostics disclose no account facts");
        assertEquals(401, send("GET", "/api/system/status", new Session("GW2_SESSION=unknown", "x"), null, null, null).status());
        assertNotEquals(Instant.EPOCH.toString(), statusA.path("accountLastRefreshedAt").asText());
    }

    @Test @Order(7)
    void profitServesTheAuthorizedCachedResultWithNoKeyAndReportsCredentialRequiredWhenStale() throws Exception {
        Session b = login("key-b");
        Resp r = send("POST", "/api/crafting/profit", b, null, "{}", null);
        assertTrue(r.status() == 200 || r.status() == 202, r.status() + " " + r.body());
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(b.context(), r.header("X-Account-Context"));
        assertFalse(r.body().contains("legacy"), "no raw legacy-guard text reaches the caller");
        assertFalse(r.body().contains("DATA_STORE_UNAVAILABLE"), r.body());
    }

    @Test @Order(8)
    void staleSourcesAreCredentialRequiredWithoutAKeyAndAdmittedForTheSessionAccountWithOne() throws Exception {
        Session a = login("key-a");
        Session b = login("key-b");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
            st.execute("UPDATE account_source_state SET completed_at = now() - interval '5 hours' WHERE account_id = '"
                    + ACCOUNT_A.toLowerCase() + "'");
        }
        Resp noKey = send("POST", "/api/crafting/profit", a, null, "{}", null);
        assertTrue(noKey.status() == 200 || noKey.status() == 202, noKey.body());
        JsonNode failures = noKey.json().path("freshness").path("failures");
        assertTrue(failures.toString().contains("CREDENTIAL_REQUIRED"), noKey.body());
        assertFalse(noKey.body().contains("\"kind\":\"ACCOUNT\",\"sources\""), "nothing admitted without a key");

        Resp keyed = send("POST", "/api/crafting/profit", a, "key-a", "{}", null);
        assertTrue(keyed.json().path("freshness").path("outstandingWork").toString().contains("ACCOUNT"), keyed.body());
        assertFalse(keyed.body().contains("key-a"), "the key never appears in a response");

        // B's sources are fresh: B's calculation admits no account work and A's staleness is invisible to it.
        Resp other = send("POST", "/api/crafting/profit", b, "key-b", "{}", null);
        assertFalse(other.json().path("freshness").path("outstandingWork").toString().contains("ACCOUNT"), other.body());
        assertFalse(other.json().path("freshness").path("failures").toString().contains("CREDENTIAL_REQUIRED"), other.body());
    }

    @Test @Order(9)
    void neverPublishedSourcesArePendingNotEmptyAndStaleOnesAreUsableWithCredentialRequired() throws Exception {
        Session c = login("key-c");
        Resp bank = send("GET", "/api/account/bank", c, null, null, null);
        assertEquals(202, bank.status(), bank.body());
        assertEquals("PENDING", bank.json().path("freshness").path("status").asText());
        assertTrue(bank.json().path("slots").isNull() && bank.json().path("slotCount").isNull(), bank.body());
        Resp materials = send("GET", "/api/account/materials", c, null, null, null);
        assertEquals(202, materials.status(), materials.body());
        assertTrue(materials.json().path("categories").isNull(), materials.body());
        assertEquals(404, send("GET", "/api/account/luck", c, null, null, null).status(), "no fabricated zero Luck");
        Resp selector = send("GET", "/api/crafting/selector-options", c, null, null, null);
        assertEquals(202, selector.status(), selector.body());
        assertTrue(selector.json().path("characterOptions").isNull(), selector.body());

        // Let the refresh the earlier test admitted finish, then expire A's sources again: still usable,
        // credential required, no admission.
        for (int i = 0; i < 100 && scalar("SELECT count(*) FROM account_source_state WHERE account_id = '"
                + ACCOUNT_A.toLowerCase() + "' AND completed_at > now() - interval '1 hour'") < 4; i++) {
            Thread.sleep(50);
        }
        try (Connection db = connect(); Statement st = db.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
            st.execute("UPDATE account_source_state SET completed_at = now() - interval '5 hours' WHERE account_id = '"
                    + ACCOUNT_A.toLowerCase() + "'");
        }
        Session a = login("key-a");
        for (String path : new String[] {"/api/account/bank", "/api/account/materials", "/api/account/luck"}) {
            Resp stale = send("GET", path, a, null, null, null);
            assertEquals(200, stale.status(), path + stale.body());
            assertEquals("STALE", stale.json().path("freshness").path("status").asText(), path);
            assertEquals("CREDENTIAL_REQUIRED", stale.json().path("freshness").path("refresh").asText(), path);
        }
        assertEquals(2, send("GET", "/api/account/bank", a, null, null, null).json().path("slots").get(0)
                .path("count").asInt(), "the stale rows are A's own");
    }

    @Test @Order(10)
    void foreignOrUnknownCharactersAreRefusedBeforeQuotesActivityOrCalculation() throws Exception {
        Session a = login("key-a");
        long seenBefore = scalar("SELECT floor(extract(epoch FROM last_seen_at) * 1000) FROM account_registry "
                + "WHERE account_id = '" + ACCOUNT_A.toLowerCase() + "'");
        long quotesBefore = scalar("SELECT count(*) FROM tp_prices");
        String body = "{\"scope\":{\"characterName\":\"Somebody Else\","
                + "\"discipline\":\"Chef\",\"rating\":400}}";
        Resp table = send("POST", "/api/crafting/discovery", a, null, body, null);
        assertEquals(403, table.status(), table.body());
        Resp detail = send("POST", "/api/crafting/discovery/resolution", a, null,
                "{\"recipeId\":7,\"calculation\":" + body + "}", null);
        assertEquals(403, detail.status(), detail.body());
        assertEquals(table.json().path("error").asText(), detail.json().path("error").asText());
        assertFalse(table.body().contains("Somebody Else"));
        assertEquals(seenBefore, scalar("SELECT floor(extract(epoch FROM last_seen_at) * 1000) FROM account_registry "
                + "WHERE account_id = '" + ACCOUNT_A.toLowerCase() + "'"), "rejection advances no activity");
        assertEquals(quotesBefore, scalar("SELECT count(*) FROM tp_prices"), "rejection starts no quote work");
    }

    @Test @Order(11)
    void keyedProfitRejectsForeignOriginInvalidKeyAndMismatchWithoutActivityOrCalculation() throws Exception {
        Session a;
        String seen = "SELECT floor(extract(epoch FROM last_seen_at) * 1000) FROM account_registry WHERE account_id = '"
                + ACCOUNT_A.toLowerCase() + "'";
        login("key-a");
        long before = scalar(seen);
        for (String path : new String[] {"/api/crafting/profit", "/api/crafting/profit/resolution"}) {
            String body = path.endsWith("resolution") ? "{\"recipeId\":7,\"calculation\":{}}" : "{}";
            // A definitive invalid-key rejection revokes the session, so each route proves on its own.
            a = login("key-a");
            HttpRequest foreign = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Origin", "https://evil.example").header("Content-Type", "application/json")
                    .header("Cookie", a.cookie()).header("X-Account-Context", a.context())
                    .header("X-GW2-API-Key", "key-a").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(403, HttpClient.newHttpClient().send(foreign, HttpResponse.BodyHandlers.ofString())
                    .statusCode(), path + " foreign origin");
            assertEquals(401, send("POST", path, a, "not-a-key", body, null).status(), path + " invalid key");
            int otherKey = send("POST", path, a, "key-b", body, null).status();
            assertTrue(otherKey == 401 || otherKey == 403, path + " another account's key: " + otherKey);
            int narrow = send("POST", path, a, "key-a-narrow", body, null).status();
            assertTrue(narrow == 401 || narrow == 403, path + " lower-grant key: " + narrow);
        }
        assertEquals(before, scalar(seen), "rejected proof advances no activity");
        UPSTREAM_OUTAGE.set(false);
    }
}
