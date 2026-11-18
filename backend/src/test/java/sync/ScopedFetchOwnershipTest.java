package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import model.BankSlot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import parser.CharacterNamesParser;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review findings on STORY-APP-015: a configured-key fetch must never publish one account's payload
 * under another account's scope, and a malformed roster-name entry must never become a false empty
 * or partial replacement. Offline: the GW2 API is replaced by a fake {@link AccountSync.AuthFetcher};
 * the database is a disposable production-migration schema.
 */
class ScopedFetchOwnershipTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "10000000-0000-0000-0000-00000000000a";
    private static final String B = "10000000-0000-0000-0000-00000000000b";

    private final String schema = "test_fetch_owner_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    private static AccountSync.AuthFetcher credentialOf(String accountId, String characters) {
        return url -> {
            if (url.endsWith("/v2/account")) return JSON.readTree("{\"id\":\"" + accountId + "\"}");
            if (url.endsWith("/v2/account/bank")) return JSON.readTree("[{\"id\":99,\"count\":5}]");
            if (url.endsWith("/v2/account/materials")) {
                return JSON.readTree("[{\"id\":99,\"category\":5,\"count\":5}]");
            }
            if (url.endsWith("/v2/account/recipes")) return JSON.readTree("[77]");
            if (url.endsWith("/v2/account/luck")) return JSON.readTree("[{\"id\":\"luck\",\"value\":9}]");
            if (url.endsWith("/v2/characters")) return JSON.readTree(characters);
            throw new AssertionError("unexpected fetch " + url);
        };
    }

    @Test
    void normalGatewayRepairsOnlyTheVerifiedAccountsPublishedItemMetadata() throws Exception {
        java.util.List<Integer> requested = new java.util.ArrayList<>();
        AccountRefreshGateway gateway = new AccountRefreshGateway() {
            @Override
            protected JsonNode fetchItemMetadata(java.util.List<Integer> ids) throws Exception {
                requested.addAll(ids);
                assertEquals(0, count("SELECT count(*) FROM account_bank WHERE account_id='" + A + "'"),
                        "metadata must be available before the account generation is published");
                return JSON.readTree("""
                        [{"id":99,"name":"Bank item","type":"Trophy","rarity":"Basic","vendor_value":2,"flags":[],
                          "icon":"https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png"}]
                        """);
            }
        };
        seedBankForB();
        gateway.syncAccountBank(AccountScope.of(A), credentialOf(A, "[]"));
        assertEquals(java.util.List.of(99), requested);
        assertEquals(1, count("SELECT count(*) FROM items WHERE item_id=99 AND icon_url IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM items WHERE item_id=99 AND no_mystic_forge=false"),
                "complete metadata includes explicit Forge eligibility, so the next refresh can reuse it");
        gateway.syncAccountBank(AccountScope.of(A), credentialOf(A, "[]"));
        assertEquals(java.util.List.of(99), requested, "repeat sync must reuse existing canonical metadata");
    }

    @Test
    void gatewayAllowsOrdinaryMetadataOutageButAbortsInterruptedBankAndRecoversExplicitly() throws Exception {
        var scopeA = AccountScope.of(A);
        var scopeB = AccountScope.of(B);
        Instant seeded = Instant.parse("2026-11-17T00:00:00Z");
        try (Connection con = Db.open()) {
            AccountSync.publishBank(con, scopeA, List.of(new BankSlot(0, 31, 7, null, null, null, null, null)), Clock.fixed(seeded, ZoneOffset.UTC));
            AccountSync.publishBank(con, scopeB, List.of(new BankSlot(0, 32, 70, null, null, null, null, null)), Clock.fixed(seeded, ZoneOffset.UTC));
        }
        try (Statement s = admin.createStatement()) {
            s.execute("INSERT INTO recipe_catalog_publication (complete) VALUES (true)");
        }
        var states = new repo.AccountSourceStateRepository();
        var registry = new repo.AccountRegistryRepository();
        var bank = new repo.BankRepository();
        var bankB = bank.loadBankSlots(admin, scopeB);
        var stateB = states.read(admin, scopeB, repo.AccountSyncStateRepository.BANK).orElseThrow();
        var activityB = registry.find(admin, scopeB).orElseThrow();
        assertEquals(1, stateB.generation());
        assertEquals(seeded, stateB.completedAt());
        List<List<Integer>> metadataCalls = new ArrayList<>();
        var interrupted = new InterruptedException("controlled metadata interruption");
        var gateway = new AccountRefreshGateway() {
            @Override protected JsonNode fetchItemMetadata(List<Integer> ids) throws Exception {
                metadataCalls.add(List.copyOf(ids));
                if (metadataCalls.size() == 1) throw new java.io.IOException("controlled metadata outage");
                if (metadataCalls.size() == 2) throw interrupted;
                return JSON.readTree("""
                        [{"id":100,"name":"Recovered Bank item","type":"Trophy","rarity":"Fine","vendor_value":12,
                          "flags":["NoMysticForge"],"icon":"https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png"}]
                        """);
            }
        };
        var payload = new java.util.concurrent.atomic.AtomicReference<>("[{\"id\":99,\"count\":5}]");
        List<String> authenticatedCalls = new ArrayList<>();
        AccountSync.AuthFetcher verifiedA = url -> {
            authenticatedCalls.add(url);
            if (url.endsWith("/v2/account/bank")) return JSON.readTree(payload.get());
            if (url.endsWith("/v2/account")) return JSON.readTree("{\"id\":\"" + A + "\"}");
            throw new AssertionError("unexpected account endpoint " + url);
        };

        gateway.syncAccountBank(scopeA, verifiedA);

        var lastGoodBank = bank.loadBankSlots(admin, scopeA);
        assertEquals(List.of(99), lastGoodBank.stream().map(repo.BankRepository.BankSlotRow::itemId).toList());
        assertEquals(5, lastGoodBank.getFirst().count());
        assertTrue(lastGoodBank.getFirst().protectionSelectable(), "unknown Forge fact remains conservatively selectable even with a complete recipe catalog");
        assertEquals(0, count("SELECT count(*) FROM items WHERE item_id=99"));
        var lastGoodState = states.read(admin, scopeA, repo.AccountSyncStateRepository.BANK).orElseThrow();
        var lastGoodActivity = registry.find(admin, scopeA).orElseThrow();
        assertEquals(2, lastGoodState.generation(), "ordinary metadata outage does not suppress valid Bank publication");
        assertEquals(bankB, bank.loadBankSlots(admin, scopeB));
        assertEquals(stateB, states.read(admin, scopeB, repo.AccountSyncStateRepository.BANK).orElseThrow());
        assertEquals(activityB, registry.find(admin, scopeB).orElseThrow());

        payload.set("[{\"id\":100,\"count\":9}]");
        try {
            assertSame(interrupted, assertThrows(InterruptedException.class, () -> gateway.syncAccountBank(scopeA, verifiedA)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(lastGoodBank, bank.loadBankSlots(admin, scopeA));
        assertEquals(lastGoodState, states.read(admin, scopeA, repo.AccountSyncStateRepository.BANK).orElseThrow());
        assertEquals(lastGoodActivity, registry.find(admin, scopeA).orElseThrow());
        assertEquals(0, count("SELECT count(*) FROM items WHERE item_id=100"));
        assertEquals(bankB, bank.loadBankSlots(admin, scopeB));
        assertEquals(stateB, states.read(admin, scopeB, repo.AccountSyncStateRepository.BANK).orElseThrow());
        assertEquals(activityB, registry.find(admin, scopeB).orElseThrow());

        gateway.syncAccountBank(scopeA, verifiedA);

        var recovered = bank.loadBankSlots(admin, scopeA);
        assertEquals(List.of(100), recovered.stream().map(repo.BankRepository.BankSlotRow::itemId).toList());
        assertEquals(9, recovered.getFirst().count());
        assertEquals("Recovered Bank item", recovered.getFirst().itemName());
        assertFalse(recovered.getFirst().protectionSelectable(), "explicit NoMysticForge completes the shared eligibility fact");
        assertEquals(3, states.read(admin, scopeA, repo.AccountSyncStateRepository.BANK).orElseThrow().generation());
        assertEquals(List.of(List.of(99), List.of(100), List.of(100)), metadataCalls);
        assertEquals(java.util.Collections.nCopies(3, List.of("https://api.guildwars2.com/v2/account", "https://api.guildwars2.com/v2/account/bank"))
                .stream().flatMap(List::stream).toList(), authenticatedCalls);
        assertEquals(bankB, bank.loadBankSlots(admin, scopeB));
        assertEquals(stateB, states.read(admin, scopeB, repo.AccountSyncStateRepository.BANK).orElseThrow());
        assertEquals(activityB, registry.find(admin, scopeB).orElseThrow());
        assertEquals(2, count("SELECT count(*) FROM account_registry"));
    }

    private long count(String sql) throws Exception {
        try (Statement st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void seedBankForB() throws Exception {
        try (Connection con = Db.open()) {
            AccountSync.publishBank(con, AccountScope.of(B),
                    List.of(new BankSlot(0, 1, 1, null, null, null, null, null)), java.time.Clock.systemUTC());
        }
    }

    @Test
    void credentialOfAccountACannotPublishUnderScopeB() throws Exception {
        seedBankForB();
        long registryBefore = count("SELECT count(*) FROM account_registry");
        AccountSync.AuthFetcher keyOfA = credentialOf(A, "[]");
        AccountScope scopeB = AccountScope.of(B);

        assertThrows(IllegalStateException.class, () -> AccountSync.syncAccountBank(scopeB, keyOfA));
        assertThrows(IllegalStateException.class, () -> AccountSync.syncAccountMaterials(scopeB, keyOfA));
        assertThrows(IllegalStateException.class, () -> AccountSync.syncAccountRecipes(scopeB, keyOfA));
        assertThrows(IllegalStateException.class, () -> AccountSync.syncAccountLuck(scopeB, keyOfA));
        assertThrows(IllegalStateException.class,
                () -> CharacterSync.syncCharactersCraftingAndRecipes(scopeB, keyOfA));

        assertEquals(1, count("SELECT count(*) FROM account_bank WHERE account_id = '" + B + "' AND item_id = 1"));
        assertEquals(1, count("SELECT count(*) FROM account_bank"));
        assertEquals(0, count("SELECT count(*) FROM account_materials"));
        assertEquals(0, count("SELECT count(*) FROM account_recipes"));
        assertEquals(0, count("SELECT count(*) FROM characters"));
        assertEquals(1, count("SELECT count(*) FROM account_source_state"));
        assertEquals(1, count("SELECT generation FROM account_source_state WHERE account_id = '" + B + "'"));
        assertEquals(registryBefore, count("SELECT count(*) FROM account_registry"));
        assertEquals(0, count("SELECT count(*) FROM account_registry WHERE account_id = '" + A + "'"));
    }

    @Test
    void unprovenOwnerIsRefusedBeforeAnyDataFetch() {
        AccountSync.AuthFetcher noIdentity = url -> {
            if (url.endsWith("/v2/account")) return JSON.readTree("{}");
            throw new AssertionError("data endpoint fetched before ownership was proven: " + url);
        };
        assertThrows(IllegalArgumentException.class, () -> AccountSync.syncAccountBank(AccountScope.of(A), noIdentity));
    }

    @Test
    void matchingCredentialStillPublishes() throws Exception {
        AccountSync.syncAccountBank(AccountScope.of(A), credentialOf(A.toUpperCase(), "[]"));
        assertEquals(1, count("SELECT count(*) FROM account_bank WHERE account_id = '" + A + "'"));
    }

    @Test
    void malformedRosterNamesAbortWithoutReplacingLastGood() throws Exception {
        AccountScope scope = AccountScope.of(A);
        for (String bad : List.of("[null]", "[\"\"]", "[\"  \"]", "[5]", "[{\"n\":1}]", "[\"Valid\",null]",
                "[[\"Valid\"]]", "{\"a\":1}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CharacterSync.syncCharactersCraftingAndRecipes(scope, credentialOf(A, bad)), bad);
        }
        assertEquals(0, count("SELECT count(*) FROM account_source_state WHERE source = 'CHARACTERS'"));
        assertEquals(0, count("SELECT count(*) FROM characters"));
    }

    @Test
    void genuineEmptyRosterIsStillASuccessfulEmptyPublication() throws Exception {
        CharacterSync.syncCharactersCraftingAndRecipes(AccountScope.of(A), credentialOf(A, "[]"));
        assertEquals(1, count("SELECT generation FROM account_source_state WHERE source = 'CHARACTERS'"));
        assertEquals(0, count("SELECT count(*) FROM characters"));
    }

    @Test
    void incompleteDetailPreservesSeededRosterChildrenAndMarker() throws Exception {
        AccountScope scope = AccountScope.of(A);
        String detail = "{\"name\":\"Ana\",\"profession\":\"Guardian\",\"race\":\"Human\","
                + "\"gender\":\"Female\",\"level\":80,\"created\":\"2020-01-01T00:00:00Z\","
                + "\"age\":12631700,\"deaths\":9193,"
                + "\"crafting\":[{\"discipline\":\"Artificer\",\"rating\":400}],"
                + "\"recipes\":[77],\"bags\":[],\"equipment\":[]}";
        AccountSync.AuthFetcher good = url -> url.endsWith("/characters/Ana")
                ? JSON.readTree(detail) : credentialOf(A, "[\"Ana\"]").get(url);
        CharacterSync.syncCharactersCraftingAndRecipes(scope, good);
        var states = new repo.AccountSourceStateRepository();
        var before = states.read(admin, scope, repo.AccountSyncStateRepository.CHARACTERS).orElseThrow();
        var registry = new repo.AccountRegistryRepository();
        var activityBefore = registry.find(admin, scope).orElseThrow();
        AccountSync.AuthFetcher incomplete = url -> url.endsWith("/characters/Ana")
                ? JSON.readTree(detail.replace("\"profession\":\"Guardian\",", ""))
                : credentialOf(A, "[\"Ana\"]").get(url);
        assertThrows(IllegalStateException.class,
                () -> CharacterSync.syncCharactersCraftingAndRecipes(scope, incomplete));
        assertEquals(before, states.read(admin, scope, repo.AccountSyncStateRepository.CHARACTERS).orElseThrow());
        assertEquals(activityBefore, registry.find(admin, scope).orElseThrow());
        assertEquals(1, count("SELECT count(*) FROM characters WHERE name = 'Ana' AND profession = 'Guardian'"));
        assertEquals(12631700, count("SELECT age_seconds FROM characters WHERE name = 'Ana'"));
        for (String badAge : new String[] {"\"age\":12631700,", "\"age\":-1,", "\"age\":1.5,", "\"age\":\"9\",",
                "\"age\":9223372036854775808,", "\"age\":null,"}) {
            AccountSync.AuthFetcher malformed = url -> url.endsWith("/characters/Ana")
                    ? JSON.readTree(badAge.equals("\"age\":12631700,") ? detail.replace(badAge, "")
                        : detail.replace("\"age\":12631700,", badAge))
                    : credentialOf(A, "[\"Ana\"]").get(url);
            assertThrows(IllegalStateException.class,
                    () -> CharacterSync.syncCharactersCraftingAndRecipes(scope, malformed), badAge);
            assertEquals(before, states.read(admin, scope, repo.AccountSyncStateRepository.CHARACTERS).orElseThrow());
            assertEquals(12631700, count("SELECT age_seconds FROM characters WHERE name = 'Ana'"));
        }
        assertEquals(1, count("SELECT count(*) FROM character_crafting WHERE rating = 400"));
        assertEquals(1, count("SELECT count(*) FROM character_recipes WHERE recipe_id = 77"));
    }

    @Test
    void strictParserKeepsValidNamesAndRejectsAnythingElse() throws Exception {
        assertEquals(List.of("Ana", "Bo"), CharacterNamesParser.parseStrict(JSON.readTree("[\"Ana\",\"Bo\"]")));
        assertTrue(CharacterNamesParser.parseStrict(JSON.readTree("[]")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CharacterNamesParser.parseStrict(null));
    }
}
