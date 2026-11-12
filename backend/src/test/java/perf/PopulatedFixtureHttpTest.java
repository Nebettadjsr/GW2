package perf;

import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import application.AccountRefreshService;
import application.GlobalDataRefreshService;
import application.CraftingGraphRebuildService;
import application.TradingPostPriceRefreshService;
import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import application.BackgroundTasks;
import web.BackgroundTaskServiceTasks;
import web.task.BackgroundTaskService;
import sync.AccountRefreshGateway;
import sync.AccountSync;
import sync.GlobalDataRefreshGateway;
import sync.TradingPostPriceRefreshGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Every reachable external edge is replaced: the proof port, the account/global/Trading Post refresh gateways
 * and the scoped account fetcher. The Trading Post gateway is the expected offline edge for the fixtures' stale
 * quotes (calls are recorded and write nothing); every other edge records an {@link #UNEXPECTED} call and fails.
 * All background work runs on a tracked executor that is drained before the schema is dropped.
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

    /** Calls that reached an external edge the fixtures must never use. */
    static final List<String> UNEXPECTED = new CopyOnWriteArrayList<>();
    /** Offline Trading Post refresh edge: admitted because the fixture quotes are stale. */
    static final List<String> TP_CALLS = new CopyOnWriteArrayList<>();
    /** Background work submitted to the application and not yet finished. */
    static final AtomicInteger OUTSTANDING = new AtomicInteger();
    /** Test-only count of HTTP requests currently inside the server (Tomcat/JDBC work), independent of client timeouts. */
    static final AtomicInteger IN_FLIGHT_REQUESTS = new AtomicInteger();
    static boolean serverIdle() { return OUTSTANDING.get() == 0 && IN_FLIGHT_REQUESTS.get() == 0; }

    static void drain() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (OUTSTANDING.get() > 0) {
            if (System.nanoTime() > deadline) fail("background work did not exit: " + OUTSTANDING.get());
            Thread.sleep(20);
        }
    }

    /** Two independent principal copies of account A's scoped data (shared catalog loaded once). */
    static SnapshotImporter.Copies copies;

    static String copyKey(int i) { return "copy-key-" + i; }

    static SnapshotImporter.Copies buildCopies(FixtureSnapshot base) throws Exception {
        java.util.Map<String, List<com.fasterxml.jackson.databind.node.ObjectNode>> only = new java.util.LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
            only.put(t, base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList());
        }
        return SnapshotImporter.principalCopies(new FixtureSnapshot(base.kind(), base.version(), only, java.util.Map.of(),
                base.sourceRevision(), null), 2);
    }

    @TestConfiguration
    static class Proofs {
        @Bean
        jakarta.servlet.Filter inFlightRequestCounter() {
            return (request, response, chain) -> {
                IN_FLIGHT_REQUESTS.incrementAndGet();
                try { chain.doFilter(request, response); } finally { IN_FLIGHT_REQUESTS.decrementAndGet(); }
            };
        }

        @Bean @Primary
        BackgroundTaskService trackedTaskService() {
            Executor pool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "kpi-fixture-task");
                t.setDaemon(true);
                return t;
            });
            return new BackgroundTaskService(job -> {
                OUTSTANDING.incrementAndGet();
                try {
                    pool.execute(() -> { try { job.run(); } finally { OUTSTANDING.decrementAndGet(); } });
                } catch (RuntimeException e) { OUTSTANDING.decrementAndGet(); throw e; }
            });
        }

        @Bean @Primary
        BackgroundTasks trackedTasks(BackgroundTaskService tasks) { return new BackgroundTaskServiceTasks(tasks); }

        private static AccountRefreshGateway noAccountGateway() {
            return new AccountRefreshGateway() {
                private void no(String what) throws Exception {
                    UNEXPECTED.add("account:" + what);
                    throw new IllegalStateException("external account edge reached: " + what);
                }
                @Override public void syncAccountBank() throws Exception { no("bank"); }
                @Override public void syncAccountMaterials() throws Exception { no("materials"); }
                @Override public void syncAccountRecipes() throws Exception { no("recipes"); }
                @Override public void syncAccountLuck() throws Exception { no("luck"); }
                @Override public void syncCharacterCraftingAndRecipes() throws Exception { no("characters"); }
                @Override public void syncAccountBank(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception { no("scoped-bank"); }
                @Override public void syncAccountMaterials(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception { no("scoped-materials"); }
                @Override public void syncAccountRecipes(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception { no("scoped-recipes"); }
                @Override public void syncAccountLuck(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception { no("scoped-luck"); }
                @Override protected com.fasterxml.jackson.databind.JsonNode fetchItemMetadata(List<Integer> ids) throws Exception {
                    no("metadata"); return null;
                }
            };
        }

        @Bean @Primary
        AccountRefreshService offlineAccountRefresh() { return new AccountRefreshService(noAccountGateway()); }

        @Bean @Primary
        ScopedAccountRefreshCoordinator offlineScopedCoordinator(AccountRefreshService refresh, BackgroundTasks tasks,
                                                                  AccountAccessService access) {
            return new ScopedAccountRefreshCoordinator(refresh, tasks, access, key -> url -> {
                UNEXPECTED.add("fetch:" + url.substring(url.indexOf("/v2/")));
                throw new java.io.IOException("external fetch refused");
            });
        }

        @Bean @Primary
        GlobalDataRefreshService offlineGlobalRefresh(CraftingGraphCache cache) {
            return new GlobalDataRefreshService(new GlobalDataRefreshGateway() {
                private void no(String what) { UNEXPECTED.add("global:" + what); throw new IllegalStateException(what); }
                @Override public void syncMaterialCategories() { no("categories"); }
                @Override public void syncMaterialItemMetadata() { no("metadata"); }
                @Override public boolean syncTpTradeableItems() { no("tradeable"); return false; }
                @Override public boolean syncAllRecipesGlobalSafe() { no("recipes"); return false; }
            }, new CraftingGraphRebuildService(cache));
        }

        @Bean @Primary
        TradingPostPriceRefreshService offlinePriceRefresh() {
            return new TradingPostPriceRefreshService(new TradingPostPriceRefreshGateway() {
                @Override public void syncTpPricesForDiscovery() { TP_CALLS.add("discovery"); }
                @Override public void syncTpPricesForProfit() { TP_CALLS.add("profit"); }
                @Override public void syncTpPricesForDiscovery(model.AccountScope scope) { TP_CALLS.add("discovery"); }
                @Override public void syncTpPricesForProfit(model.AccountScope scope) { TP_CALLS.add("profit"); }
            });
        }

        @Bean @Primary
        AccountProofPort syntheticProofPort() {
            return key -> {
                for (String account : SyntheticFixtures.ACCOUNTS) {
                    if (SyntheticFixtures.keyOf(account).equals(key)) {
                        return new AccountProof(account, account.equals(SyntheticFixtures.E) ? ACCOUNT_ONLY : ALL, Set.of(), null);
                    }
                }
                for (int i = 0; i < copies.accountIds().size(); i++) {
                    if (copyKey(i).equals(key)) return new AccountProof(copies.accountIds().get(i), ALL, Set.of(), null);
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
        FixtureSnapshot base = SyntheticFixtures.build();
        copies = buildCopies(base);
        java.util.Map<String, List<com.fasterxml.jackson.databind.node.ObjectNode>> merged = new java.util.LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            List<com.fasterxml.jackson.databind.node.ObjectNode> rows = new java.util.ArrayList<>(base.rows(t));
            if (FixtureSnapshot.SCOPED_TABLES.contains(t)) rows.addAll(copies.snapshot().rows(t));
            merged.put(t, rows);
        }
        try (Connection c = PopulatedFixtureIsolationIT.connect()) {
            FixtureLoader.load(c, SCHEMA, new FixtureSnapshot(base.kind(), base.version(), merged, base.expected(), base.sourceRevision(), null));
        }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY,
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json").toString());
    }

    @AfterEach
    void noLiveEdgeAndNoWorkerLeft() throws Exception {
        drain();
        assertEquals(List.of(), UNEXPECTED, "no external edge other than the offline price edge may be reached");
    }

    @AfterAll
    static void cleanup() throws Exception {
        drain(); // owned workers must have exited before the schema configuration is cleared and dropped
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json"));
        PopulatedFixtureIsolationIT.drop(SCHEMA);
    }

    /** One independent logged-in principal. */
    private final class Principal {
        final String cookie, context;
        Principal(String account) throws Exception { this(SyntheticFixtures.keyOf(account), true); }
        Principal(String key, boolean isKey) throws Exception {
            HttpResponse<String> r = raw("POST", "/api/session", "{}", key, null, null);
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
        // The stale fixture quotes admitted a Trading Post refresh; it ran on the offline fake edge only.
        drain();
        assertFalse(TP_CALLS.isEmpty(), "stale quotes must exercise the offline price edge");
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
    void mismatchedCookieContextIsDeniedAndCombinedProtectionLeavesNothingForEachPrincipal() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        // A's session cookie with B's account context (and the reverse) must never serve either principal's bank.
        for (Principal[] p : new Principal[][] {{a, b}, {b, a}}) {
            int status = raw("GET", "/api/account/bank", null, null, p[0].cookie, p[1].context).statusCode();
            assertTrue(status == 401 || status == 403, "mismatched cookie/context: " + status);
        }
        // Hand arithmetic (see the per-principal profit test): A item 1 = 250, item 2 = 180; B item 1 = 12, item 2 = 9.
        String both = ",\"materialProtectionEnabled\":true,\"protectedBankItemIds\":[1,2],\"protectedMaterialItemIds\":[1,2]";
        assertEquals(0, row(a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, both))), 20001).path("craftableCount").asInt(),
                "A: protecting every bank and material unit leaves nothing to craft");
        assertEquals(0, row(b.json(b.post("/api/crafting/profit", String.format(OWN_MATS, both))), 20001).path("craftableCount").asInt(),
                "B: same");
        assertEquals(60, row(a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, ""))), 20001).path("craftableCount").asInt(),
                "protection settings of one request do not persist into the next");
    }

    @Test
    void actualSerializedBankAndMaterialsDtosSatisfyTheOracleAndTheCharacterInventoryProtectionExampleIsNonzero() throws Exception {
        java.util.Map<String, List<com.fasterxml.jackson.databind.node.ObjectNode>> only = new java.util.LinkedHashMap<>();
        FixtureSnapshot base = SyntheticFixtures.build();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
            only.put(t, base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList());
        }
        PrincipalOracle o = PrincipalOracle.from(SnapshotImporter.principalCopies(
                new FixtureSnapshot(base.kind(), base.version(), only, java.util.Map.of(), base.sourceRevision(), null), 1));
        Principal a = new Principal(SyntheticFixtures.A);
        JsonNode bank = a.json(a.get("/api/account/bank"));
        JsonNode mats = a.json(a.get("/api/account/materials"));
        assertTrue(o.verifyBank(0, bank), o.mismatch());
        assertTrue(o.verifyMaterials(0, mats), o.mismatch());
        // Hand-written: four stored stock rows, six displayed catalog positions (items 5 and 6 zero-filled).
        int positions = 0;
        for (JsonNode c : mats.path("categories")) positions += c.path("materials").size();
        assertEquals(6, positions);
        // Specific manual zero case (kept): protecting items 1 and 2 in both sources leaves recipe 20001 nothing.
        // Nonzero case: recipe 20002 needs 2 x item 3 + 1 x item 100. A's item 3 is 40 material + 7 in character Aria's
        // inventory. Protecting item 3 and 100 in BOTH bank and materials leaves the 7 inventory units => 3 crafts of
        // 20002 (item 100 is craftable from the unprotected items 1 and 2), proving protection does not suppress
        // character-inventory stock (DOMAIN_SPEC section 9).
        String protectedBoth = ",\"materialProtectionEnabled\":true,\"protectedBankItemIds\":[3,100],\"protectedMaterialItemIds\":[3,100]";
        assertEquals(3, row(a.json(a.post("/api/crafting/profit", String.format(OWN_MATS, protectedBoth))), 20002).path("craftableCount").asInt(),
                "character-inventory stock remains craftable after bank and materials are protected");
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

    @Test
    void twoPrincipalCopiesOfTheSingleAccountSnapshotHaveIndependentSessionsAndResults() throws Exception {
        Principal p0 = new Principal(copyKey(0), true);
        Principal p1 = new Principal(copyKey(1), true);
        assertNotEquals(p0.context, p1.context, "independent account contexts");
        assertNotEquals(p0.cookie, p1.cookie, "independent sessions");
        // Each copy carries account A's hand-written outcomes (3 slots / 85 items, 400 materials, 60 crafts).
        for (Principal p : new Principal[] {p0, p1}) {
            JsonNode bank = p.json(p.get("/api/account/bank"));
            assertEquals(3, bank.path("slotCount").asInt());
            assertEquals(85, bankCountSum(bank));
            assertEquals(400, materialTotal(p.json(p.get("/api/account/materials"))));
            assertEquals(60, row(p.json(p.post("/api/crafting/profit", String.format(OWN_MATS, ""))), 20001).path("craftableCount").asInt());
            assertEquals(403, p.get("/api/system/status/details").statusCode(), "a copy is never the owner");
        }
        // Dependent references: each copy sees only its own renamed characters, never the other's or the original's.
        String r0 = p0.get("/api/account/characters").body();
        String r1 = p1.get("/api/account/characters").body();
        assertTrue(r0.contains("Aria c0") && !r0.contains("Aria c1"), "copy 0 roster");
        assertTrue(r1.contains("Aria c1") && !r1.contains("Aria c0"), "copy 1 roster");
        assertEquals(404, p0.get("/api/account/characters/Aria%20c1").statusCode());
        assertEquals(200, p1.get("/api/account/characters/Aria%20c1/inventory").statusCode());
        assertEquals(404, p0.get("/api/account/characters/Aria%20c1/inventory").statusCode());
        assertEquals(2, p0.json(p0.get("/api/system/status")).at("/accountOverview/characters/count").asInt());
    }
}
