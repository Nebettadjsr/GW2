package sync;

import application.CharacterInventoryLayoutService;
import application.CharacterInventoryLayoutService.State;
import application.access.AccountContext;
import application.access.AccountGrant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import model.CharacterBagRow;
import model.CharacterInfo;
import model.CharacterItemRow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import parser.CharacterBagManifestParser;
import repo.CharacterInventoryLayoutRepository;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-CHAR-006: bag manifest publication and the lazy layout read, on a disposable schema. */
class CharacterInventoryLayoutTest {
    private static final String A_ID = "10000000-0000-0000-0000-0000000000a1";
    private static final String B_ID = "10000000-0000-0000-0000-0000000000b2";
    private static final AccountScope A = AccountScope.of(A_ID);
    private static final AccountScope B = AccountScope.of(B_ID);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final String schema = "test_bag_layout_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private int queries;

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

    private static CharacterSync.CharPayload payload(String name, List<CharacterBagRow> bags, CharacterItemRow... items) {
        return new CharacterSync.CharPayload(new CharacterInfo(name, "Engineer", "Norn", "Male", 80, null),
                List.of(), List.of(), List.of(items), bags);
    }

    private static CharacterItemRow bagItem(int bag, int slot, int id, int count) {
        return new CharacterItemRow("BAG", bag, slot, null, id, count, null, null);
    }

    private void publish(AccountScope scope, CharacterSync.CharPayload... payloads) throws Exception {
        try (Connection c = connect()) {
            c.setSchema(schema);
            CharacterSync.publishRoster(c, scope, List.of(payloads), NOW);
        }
    }

    private CharacterInventoryLayoutService service() {
        return new CharacterInventoryLayoutService(null, new CharacterInventoryLayoutRepository(), () -> {
            Connection c;
            try { c = connect(); c.setSchema(schema); } catch (Exception e) { throw new SQLException(e); }
            return countingConnection(c);
        }, NOW, Duration.ofHours(1));
    }

    private Connection countingConnection(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")
                            || method.getName().equals("prepareCall")) queries++;
                    try { return method.invoke(real, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private static AccountContext ctx(String id) {
        return new AccountContext(id, EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES),
                Set.of(), "ctx", Instant.now().plusSeconds(60));
    }

    private long count(String sql) throws Exception {
        try (var st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static List<CharacterBagRow> manifest() {
        return List.of(new CharacterBagRow(0, true, 8932, 3), new CharacterBagRow(1, false, null, null),
                new CharacterBagRow(2, true, 8933, 2));
    }

    // ---- parser boundaries (AC 1, 2) ----

    private static JsonNode json(String s) throws Exception { return M.readTree(s); }

    @Test
    void parserKeepsInstalledAndNullPositionsAndAcceptsBoundarySlots() throws Exception {
        var rows = CharacterBagManifestParser.parse(json(
                "[{\"id\":5,\"size\":2,\"inventory\":[null,{\"id\":1,\"count\":1}]},null,{\"id\":6,\"size\":1,\"inventory\":[null]}]"));
        assertEquals(List.of(new CharacterBagRow(0, true, 5, 2), new CharacterBagRow(1, false, null, null),
                new CharacterBagRow(2, true, 6, 1)), rows);
    }

    @Test
    void parserRejectsContradictoryPayloads() throws Exception {
        for (String bad : new String[] {
                "[{\"id\":5,\"size\":2,\"inventory\":[null,null,{\"id\":1,\"count\":1}]}]",   // slot == capacity
                "[{\"id\":5,\"size\":2,\"inventory\":[]}]",                                   // invented trailing empties
                "[{\"id\":5,\"size\":2,\"inventory\":[null]}]",                               // shorter than capacity
                "[{\"id\":5,\"size\":2,\"inventory\":[null,null,null]}]",                     // overlong all-null tail
                "[{\"id\":5,\"size\":0,\"inventory\":[]}]",                                      // no capacity
                "[{\"id\":5,\"inventory\":[]}]",                                                 // capacity missing
                "[{\"size\":2,\"inventory\":[]}]",                                               // container missing
                "[{\"id\":5,\"size\":2}]",                                                       // inventory missing
                "{}"}) {
            assertThrows(IllegalStateException.class, () -> CharacterBagManifestParser.parse(json(bad)), bad);
        }
    }

    // ---- publication + readback (AC 1, 3, 4, 6, 7, 9) ----

    @Test
    void layoutRoundTripsWithEmptyCellsAndUninstalledPositions() throws Exception {
        admin.createStatement().executeUpdate("INSERT INTO items(item_id,name,type,rarity,icon_url) VALUES (100,'Ore','Gathering','Basic',NULL)");
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5), bagItem(2, 0, 100, 7)));
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(State.AVAILABLE, r.state());
        assertEquals(CharacterInventoryLayoutService.Contents.HAS_ITEMS, r.contents());
        var bags = r.bags();
        assertEquals(3, bags.size());
        assertEquals(8932, bags.get(0).container().itemId());
        assertFalse(bags.get(0).container().resolved(), "missing metadata is explicit");
        assertEquals(List.of(true, true, false), bags.get(0).slots().stream().map(s -> s.empty()).toList());
        assertEquals(5, bags.get(0).slots().get(2).quantity());
        assertEquals("Ore", bags.get(0).slots().get(2).item().name());
        assertFalse(bags.get(1).installed());
        assertNull(bags.get(1).capacity());
        assertEquals(List.of(), bags.get(1).slots());
        assertEquals(7, bags.get(2).slots().get(0).quantity(), "same item id in another bag is an independent stack");
        assertEquals(List.of(false, true), bags.get(2).slots().stream().map(s -> s.empty()).toList());
    }

    @Test
    void installedBagsAllEmptyAndNoBagsAreDistinctSuccesses() throws Exception {
        publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 1, 2))), payload("Bo", List.of()));
        var empty = service().read(ctx(A_ID), A, "Ana");
        assertEquals(CharacterInventoryLayoutService.Contents.ALL_SLOTS_EMPTY, empty.contents());
        assertEquals(2, empty.bags().get(0).slots().size());
        var none = service().read(ctx(A_ID), A, "Bo");
        assertEquals(State.AVAILABLE, none.state());
        assertEquals(CharacterInventoryLayoutService.Contents.NO_BAGS, none.contents());
    }

    @Test
    void replacementLeavesNoStaleBagsOrItems() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5), bagItem(2, 1, 100, 1)));
        publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 8932, 4), new CharacterBagRow(1, false, null, null)),
                bagItem(0, 3, 101, 9)));
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(2, r.bags().size());
        assertEquals(4, r.bags().get(0).capacity());
        assertEquals(List.of(3), r.bags().get(0).slots().stream().filter(s -> !s.empty()).map(s -> s.slot()).toList());
        assertEquals(2, count("SELECT count(*) FROM character_bags"));
        assertEquals(1, count("SELECT count(*) FROM character_items WHERE location='BAG'"));
        publish(A);
        assertEquals(0, count("SELECT count(*) FROM character_bags"), "removed characters cascade");
    }

    @Test
    void failedPublicationKeepsWholePriorGeneration() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        long gen = count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'");
        // contradictory payload is rejected at the publication boundary, before any write
        assertThrows(IllegalStateException.class, () -> publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 1, 0)),
                bagItem(0, 0, 999, 1))));
        assertEquals(gen, count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'"));
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(3, r.bags().size());
        assertEquals(5, r.bags().get(0).slots().get(2).quantity());
    }

    @Test
    void validationRejectsEveryContradictionBeforeWriting() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        long gen = count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'");
        List<CharacterSync.CharPayload> bad = List.of(
                payload("Ana", manifest(), bagItem(0, 3, 100, 1)),                       // slot == capacity
                payload("Ana", manifest(), bagItem(0, -1, 100, 1)),                      // negative slot
                payload("Ana", manifest(), bagItem(0, 1, 100, 1), bagItem(0, 1, 101, 1)), // duplicate coordinate
                payload("Ana", manifest(), bagItem(1, 0, 100, 1)),                       // uninstalled bag
                payload("Ana", manifest(), bagItem(7, 0, 100, 1)),                       // missing bag
                payload("Ana", manifest(), bagItem(0, 0, 100, 0)),                       // non-positive quantity
                payload("Ana", List.of(new CharacterBagRow(0, true, 1, 2), new CharacterBagRow(0, true, 1, 2))),
                payload("Ana", List.of(new CharacterBagRow(0, true, null, 2))),
                payload("Ana", List.of(new CharacterBagRow(-1, false, null, null))));
        for (var p : bad) assertThrows(IllegalStateException.class, () -> publish(A, p));
        assertEquals(gen, count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'"));
        assertEquals(3, count("SELECT count(*) FROM character_bags"));
        assertEquals(5, service().read(ctx(A_ID), A, "Ana").bags().get(0).slots().get(2).quantity());
    }

    @Test
    void sqlFailureAfterValidReplacementBeganRollsBackEverything() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        long gen = count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'");
        exec("CREATE FUNCTION fail_marker() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$",
                "CREATE TRIGGER fail_marker BEFORE INSERT OR UPDATE ON character_bag_layout_publication"
                        + " FOR EACH ROW EXECUTE FUNCTION fail_marker()");
        // valid payload: roster, items and bag rows are written first; the marker write then fails
        assertThrows(Exception.class, () -> publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 1, 4)),
                bagItem(0, 3, 101, 9))));
        assertEquals(gen, count("SELECT generation FROM account_source_state WHERE source='CHARACTERS'"));
        assertEquals(3, count("SELECT count(*) FROM character_bags"));
        assertEquals(8932, count("SELECT container_item_id FROM character_bags WHERE bag_index=0"));
        assertEquals(100, count("SELECT item_id FROM character_items WHERE location='BAG'"));
        assertEquals(5, service().read(ctx(A_ID), A, "Ana").bags().get(0).slots().get(2).quantity());
    }

    @Test
    void databaseRejectsInstalledBagWithNullFieldsAndInvalidValues() {
        for (String values : new String[] {"true, NULL, 4", "true, 5, NULL", "true, 0, 4", "true, 5, 0",
                "false, 5, NULL", "false, NULL, 4"}) {
            assertThrows(SQLException.class, () -> exec("INSERT INTO account_registry(account_id) VALUES ('" + A_ID
                    + "') ON CONFLICT DO NOTHING",
                    "INSERT INTO characters(account_id, character_id, name, profession, race, gender, level) VALUES ('"
                            + A_ID + "', 1, 'Ana', 'Guardian', 'Human', 'Female', 80) ON CONFLICT DO NOTHING",
                    "INSERT INTO character_bags(account_id, character_id, bag_index, installed, container_item_id, capacity)"
                            + " VALUES ('" + A_ID + "', 1, 0, " + values.replace(", ", ", ") + ")"), values);
        }
    }

    @Test
    void legacyPayloadWithoutManifestEvidenceIsNeverCertifiedEmpty() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        publish(A, new CharacterSync.CharPayload(new CharacterInfo("Ana", "Engineer", "Norn", "Male", 80, null),
                List.of(), List.of(), List.of()));
        assertEquals(0, count("SELECT count(*) FROM character_bag_layout_publication"));
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(State.UNAVAILABLE, r.state());
        assertEquals("LAYOUT_REFRESH_REQUIRED", r.reason());
        publish(A, payload("Ana", List.of()));
        assertEquals(CharacterInventoryLayoutService.Contents.NO_BAGS, service().read(ctx(A_ID), A, "Ana").contents(),
                "an explicitly validated empty manifest is a genuine success");
    }

    @Test
    void accountReferencedItemIdsIncludeInstalledContainersDistinct() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 8932, 5)));
        try (Connection c = connect()) {
            c.setSchema(schema);
            assertEquals(List.of(8932, 8933), IconSync.accountReferencedItemIds(c, A));
        }
    }

    private void exec(String... statements) throws SQLException {
        try (Statement s = admin.createStatement()) { for (String q : statements) s.execute(q); }
    }

    @Test
    void generationWithoutManifestIsRefreshRequiredNotEmpty() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        try (Statement s = admin.createStatement()) {
            s.execute("DELETE FROM character_bag_layout_publication");
            s.execute("DELETE FROM character_bags");
        }
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(State.UNAVAILABLE, r.state());
        assertEquals("LAYOUT_REFRESH_REQUIRED", r.reason());
        assertNull(r.bags());
    }

    @Test
    void occupiedRowOutsideManifestIsInconsistentNotGuessed() throws Exception {
        publish(A, payload("Ana", manifest(), bagItem(0, 2, 100, 5)));
        admin.createStatement().executeUpdate("UPDATE character_items SET slot_index = 3");   // == capacity
        assertEquals("LAYOUT_REFRESH_REQUIRED", service().read(ctx(A_ID), A, "Ana").reason());
        admin.createStatement().executeUpdate("UPDATE character_items SET slot_index = 0, bag_index = 1"); // uninstalled
        assertEquals("LAYOUT_REFRESH_REQUIRED", service().read(ctx(A_ID), A, "Ana").reason());
    }

    @Test
    void pendingNotFoundAndGrantStatesAreDistinct() throws Exception {
        assertEquals(State.PENDING, service().read(ctx(A_ID), A, "Ana").state());
        publish(A, payload("Ana", manifest()));
        assertEquals(State.NOT_FOUND, service().read(ctx(A_ID), A, "Nobody").state());
        var noGrant = new AccountContext(A_ID, EnumSet.of(AccountGrant.ACCOUNT), Set.of(), "ctx", Instant.now().plusSeconds(60));
        assertEquals(State.GRANT_REQUIRED, service().read(noGrant, A, "Ana").state());
        var stale = new CharacterInventoryLayoutService(null, new CharacterInventoryLayoutRepository(), () -> {
            Connection c;
            try { c = connect(); c.setSchema(schema); } catch (Exception e) { throw new SQLException(e); }
            return c;
        }, Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(1));
        var r = stale.read(ctx(A_ID), A, "Ana");
        assertEquals(State.STALE, r.state());
        assertNotNull(r.bags());
    }

    @Test
    void accountsWithSameCharacterNameDoNotMix() throws Exception {
        publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 1, 1)), bagItem(0, 0, 100, 5)));
        publish(B, payload("Ana", List.of(new CharacterBagRow(0, true, 2, 4))));
        var a = service().read(ctx(A_ID), A, "Ana");
        var b = service().read(ctx(B_ID), B, "Ana");
        assertEquals(1, a.bags().get(0).capacity());
        assertEquals(4, b.bags().get(0).capacity());
        assertEquals(CharacterInventoryLayoutService.Contents.ALL_SLOTS_EMPTY, b.contents());
    }

    @Test
    void equipmentRowsNeverAppearAsBagStockAndCraftingRowsAreUnchanged() throws Exception {
        publish(A, payload("Ana", List.of(new CharacterBagRow(0, true, 1, 2)), bagItem(0, 0, 100, 5),
                new CharacterItemRow("EQUIPMENT", null, null, "Helm", 100, 1, null, null)));
        var r = service().read(ctx(A_ID), A, "Ana");
        assertEquals(5, r.bags().get(0).slots().stream().filter(s -> !s.empty()).mapToInt(s -> s.quantity()).sum());
        assertEquals(6, count("SELECT sum(count) FROM character_items WHERE item_id=100"),
                "character_items keeps both rows exactly as before; the layout read ignores EQUIPMENT");
    }

    @Test
    void measuresRepresentativeMaximumLayout() throws Exception {
        List<CharacterBagRow> bags = new ArrayList<>();
        List<CharacterItemRow> items = new ArrayList<>();
        for (int b = 0; b < 32; b++) {
            bags.add(new CharacterBagRow(b, true, 8000 + b, 32));
            for (int s = 0; s < 32; s += 2) items.add(bagItem(b, s, 100 + b * 40 + s, 1));
        }
        publish(A, payload("Ana", bags, items.toArray(new CharacterItemRow[0])));
        queries = 0;
        long t0 = System.nanoTime();
        var r = service().read(ctx(A_ID), A, "Ana");
        long cold = System.nanoTime() - t0;
        int coldQueries = queries;
        queries = 0;
        t0 = System.nanoTime();
        r = service().read(ctx(A_ID), A, "Ana");
        long warm = System.nanoTime() - t0;
        var wire = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                .addSerializer(Instant.class, new com.fasterxml.jackson.databind.ser.std.ToStringSerializer(Instant.class)));
        int bytes = wire.writeValueAsBytes(web.dto.CharacterInventoryResponse.respond(r).getBody()).length;
        long storage = count("SELECT pg_total_relation_size('character_bags') + pg_total_relation_size('character_items')"
                + " + pg_total_relation_size('character_bag_layout_publication')");
        System.out.printf("[CHAR-006 measure] synthetic stress (not a game maximum) bags=%d manifestRows=%d itemRows=%d"
                        + " fullResponseBytes=%d preparedStatements(cold)=%d preparedStatements(warm)=%d cold=%dms warm=%dms"
                        + " storageBytes(character_bags+character_items+layout marker, incl. indexes/toast)=%d%n",
                bags.size(), count("SELECT count(*) FROM character_bags"), items.size(), bytes, coldQueries, queries,
                cold / 1_000_000, warm / 1_000_000, storage);
        assertEquals(coldQueries, queries);
        assertEquals(8, queries, "measured prepared statements incl. schema guards, marker, character, publication,"
                + " manifest, occupied rows and one metadata batch; independent of bag count");
        assertEquals(32 * 32, r.bags().stream().mapToInt(b -> b.slots().size()).sum());
        assertTrue(bytes < 1_000_000);
    }
}
