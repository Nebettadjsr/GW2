package perf;

import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import repo.CraftingGraphCache;
import repo.Db;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Default-CI (Surefire) acceptance for STORY-KPI-007 AC2/AC3 over real HTTP: the populated synthetic
 * fixtures are loaded into a disposable Flyway schema, independent synthetic proof keys log in through the
 * running application, and the real controllers/services return per-principal outcomes that were written by
 * hand (see {@link SyntheticFixtures#expected()} and the literals below), never computed from the rows.
 * Only the proof port is replaced (no GW2 call is possible); no refresh gateway is invoked.
 */
@SpringBootTest(classes = web.Gw2ApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000",
                "gw2.owner.account-guid=" + SyntheticFixtures.A})
@Import(PopulatedFixtureHttpTest.Proofs.class)
class PopulatedFixtureHttpTest {

    static final String SCHEMA = "kpi_" + UUID.randomUUID().toString().replace("-", "");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<AccountGrant> ALL = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
            AccountGrant.UNLOCKS, AccountGrant.PROGRESSION, AccountGrant.TRADINGPOST, AccountGrant.BUILDS);
    /** Account E is granted only the ACCOUNT scope: no inventories, characters or unlocks. */
    static final Set<AccountGrant> ACCOUNT_ONLY = Set.of(AccountGrant.ACCOUNT);

    @TestConfiguration
    static class Proofs {
        @Bean @Primary
        AccountProofPort syntheticProofPort() {
            return key -> {
                for (String account : SyntheticFixtures.ACCOUNTS) {
                    if (SyntheticFixtures.keyOf(account).equals(key)) {
                        return new AccountProof(account, account.equals(SyntheticFixtures.E) ? ACCOUNT_ONLY : ALL, Set.of(), null);
                    }
                }
                throw new InvalidCredentialException("rejected");
            };
        }
    }

    @LocalServerPort int port;
    @Autowired CraftingGraphCache graphCache;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void load() throws Exception {
        PopulatedFixtureIsolationIT.createSchema(SCHEMA);
        try (Connection c = PopulatedFixtureIsolationIT.connect()) { FixtureLoader.load(c, SCHEMA, SyntheticFixtures.build()); }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY,
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json").toString());
    }

    @AfterAll
    static void cleanup() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json"));
        PopulatedFixtureIsolationIT.drop(SCHEMA);
    }

    /** One independent logged-in principal. */
    private final class Principal {
        final String cookie, context;
        Principal(String account) throws Exception {
            HttpResponse<String> r = raw("POST", "/api/session", "{}", SyntheticFixtures.keyOf(account), null, null);
            assertEquals(200, r.statusCode(), "synthetic login");
            String set = r.headers().firstValue("Set-Cookie").orElseThrow();
            cookie = set.substring(0, set.indexOf(';'));
            context = r.headers().firstValue("X-Account-Context").orElseThrow();
        }
        HttpResponse<String> get(String path) throws Exception { return raw("GET", path, null, null, cookie, context); }
        HttpResponse<String> post(String path, String body) throws Exception { return raw("POST", path, body, null, cookie, context); }
        JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String cookie, String context) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (key != null) b.header("X-GW2-API-Key", key);
        if (cookie != null) b.header("Cookie", cookie).header("X-Account-Context", context);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long bankCountSum(JsonNode bank) {
        long sum = 0;
        for (JsonNode s : bank.path("slots")) sum += s.path("count").asLong();
        return sum;
    }

    private static long materialTotal(JsonNode materials) {
        long sum = 0;
        for (JsonNode c : materials.path("categories")) for (JsonNode m : c.path("materials")) sum += m.path("count").asLong();
        return sum;
    }

    private static JsonNode row(JsonNode profit, int recipeId) {
        for (JsonNode r : profit.path("rows")) if (r.path("recipeId").asInt() == recipeId) return r;
        return null;
    }

    private static final String OWN_MATS = "{\"scope\":{\"kind\":\"DISCIPLINE\",\"discipline\":\"Chef\"},"
            + "\"settings\":{\"useOwnMats\":true,\"allowBuying\":false,\"maxBuyCopper\":1000000%s}}";

    @Test
    void bankMaterialsAndCharactersOverHttpArePartitionedPerPrincipal() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        // Hand-written outcomes: A has 3 bank slots totalling 85 and 4 material rows totalling 400;
        // B overlaps item IDs 1 and 3 but has 2 slots totalling 907 and 3 material rows totalling 91.
        JsonNode aBank = a.json(a.get("/api/account/bank"));
        JsonNode bBank = b.json(b.get("/api/account/bank"));
        assertEquals(3, aBank.path("slotCount").asInt());
        assertEquals(85, bankCountSum(aBank));
        assertEquals(2, bBank.path("slotCount").asInt());
        assertEquals(907, bankCountSum(bBank));
        assertEquals(400, materialTotal(a.json(a.get("/api/account/materials"))));
        assertEquals(91, materialTotal(b.json(b.get("/api/account/materials"))));

        String aRoster = a.get("/api/account/characters").body();
        String bRoster = b.get("/api/account/characters").body();
        assertTrue(aRoster.contains("Aria") && aRoster.contains("Bran") && !aRoster.contains("Cato"), "A roster");
        assertTrue(bRoster.contains("Cato") && bRoster.contains("Dara") && bRoster.contains("Edda") && !bRoster.contains("Aria"), "B roster");
        // Overlapping character IDs (1, 2) never cross principals; a name belonging to the other principal is absent.
        assertEquals(404, a.get("/api/account/characters/Cato").statusCode());
        assertEquals(404, b.get("/api/account/characters/Aria").statusCode());
        // Saved equipment and bag inventory of the principal's own character are served; the foreign one is not.
        assertEquals(200, a.get("/api/account/characters/Aria/equipment-tabs").statusCode());
        assertEquals(200, a.get("/api/account/characters/Aria/inventory").statusCode());
        assertEquals(404, a.get("/api/account/characters/Cato/inventory").statusCode());
    }

    @Test
    void emptyStaleAndGrantRestrictedPrincipalsStayHonestOverHttp() throws Exception {
        Principal c = new Principal(SyntheticFixtures.C);
        Principal d = new Principal(SyntheticFixtures.D);
        Principal e = new Principal(SyntheticFixtures.E);
        // C: granted but never synchronized => pending (202), not an invented empty bank.
        assertEquals(202, c.get("/api/account/bank").statusCode());
        // D: stale bank marker still serves its one stored slot (11 items).
        JsonNode dBank = d.json(d.get("/api/account/bank"));
        assertEquals(1, dBank.path("slotCount").asInt());
        assertEquals(11, bankCountSum(dBank));
        // E: only the ACCOUNT grant => inventory-backed reads are refused, nothing from the stored rows leaks.
        HttpResponse<String> denied = e.get("/api/account/bank");
        assertTrue(denied.statusCode() == 403 || denied.statusCode() == 401, "status " + denied.statusCode());
        assertFalse(denied.body().contains("99"), "E's stored bank rows must not be served without the grant");
        assertTrue(e.get("/api/account/characters").statusCode() >= 400);
    }

    @Test
    void craftingProfitIsCalculatedPerPrincipalWithOverlappingRecipesAndSourceSpecificProtection() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        // Recipe 20001 (2 x item 1 + 3 x item 2 -> dish): hand arithmetic over each principal's own stock.
        //   A: item 1 = 200 material + 50 bank = 250 => 125 crafts; item 2 = 150 + 30 = 180 => 60 crafts; min = 60.
        //   B: item 1 = 5 material + 7 bank = 12 => 6; item 2 = 9 material (no bank) => 3; min = 3.
        JsonNode aProfit = a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, "")));
        JsonNode bProfit = b.json(b.post("/api/crafting/profit", String.format(OWN_MATS, "")));
        assertNotNull(row(aProfit, 20001), "A has recipe 20001");
        assertNotNull(row(bProfit, 20001), "B has recipe 20001");
        assertEquals(60, row(aProfit, 20001).path("craftableCount").asInt(), "A craftable from own stock");
        assertEquals(3, row(bProfit, 20001).path("craftableCount").asInt(), "B craftable from own stock");
        assertNull(row(bProfit, 20002), "B never unlocked recipe 20002 (and is rated below it)");

        // Source-specific protection: protecting item 2 in the BANK only removes A's 30 banked units
        // (materials stay usable): 150 / 3 = 50 crafts. Protecting the same id in MATERIALS only removes the
        // 150 stored materials (30 bank left): 30 / 3 = 10 crafts. B's result is unaffected by A's settings.
        String bankOnly = ",\"materialProtectionEnabled\":true,\"protectedBankItemIds\":[2],\"protectedMaterialItemIds\":[]";
        String materialsOnly = ",\"materialProtectionEnabled\":true,\"protectedBankItemIds\":[],\"protectedMaterialItemIds\":[2]";
        assertEquals(50, row(a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, bankOnly))), 20001).path("craftableCount").asInt());
        assertEquals(10, row(a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, materialsOnly))), 20001).path("craftableCount").asInt());
        assertEquals(3, row(b.json(b.post("/api/crafting/profit", String.format(OWN_MATS, bankOnly))), 20001).path("craftableCount").asInt(),
                "B has no banked item 2: the bank-only protection of id 2 changes nothing for B");
    }

    @Test
    void statusIsPartitionedAndOwnerDetailsAreRestrictedToTheConfiguredOwner() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        Principal c = new Principal(SyntheticFixtures.C);
        // Saved counts: characters, recipes and material rows are each principal's own.
        JsonNode aStatus = a.json(a.get("/api/system/status"));
        JsonNode bStatus = b.json(b.get("/api/system/status"));
        assertEquals(2, aStatus.at("/accountOverview/characters/count").asInt(), aStatus.toString());
        assertEquals(3, bStatus.at("/accountOverview/characters/count").asInt(), bStatus.toString());
        assertEquals(4, aStatus.at("/accountOverview/materials/count").asInt());
        assertEquals(3, bStatus.at("/accountOverview/materials/count").asInt());
        assertEquals(2, aStatus.at("/accountOverview/recipes/count").asInt());
        assertEquals(1, bStatus.at("/accountOverview/recipes/count").asInt());
        JsonNode cStatus = c.json(c.get("/api/system/status"));
        for (String source : new String[] {"characters", "recipes", "materials"}) {
            assertEquals("NOT_LOADED", cStatus.at("/accountOverview/" + source + "/state").asText(), "C has nothing saved: " + source);
            assertTrue(cStatus.at("/accountOverview/" + source + "/count").isNull());
        }

        // Owner boundary: only the configured owner (A) reads detailed diagnostics; B is refused, anonymous is refused.
        assertEquals(200, a.get("/api/system/status/details").statusCode());
        assertEquals(403, b.get("/api/system/status/details").statusCode());
        assertEquals(403, c.get("/api/system/status/details").statusCode());
        assertEquals(401, raw("GET", "/api/system/status/details", null, null, null, null).statusCode());
        // The ordinary status response carries no owner-only aggregates for the owner either.
        assertFalse(aStatus.has("accounts"));
    }
}
