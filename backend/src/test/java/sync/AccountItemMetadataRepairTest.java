package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import repo.EnvConfig;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-COV-008: real managed-schema account metadata repair, with fixed upstream facts and real
 * late statement failure. No image/API call. A separate observer reads committed rows after repair.
 */
class AccountItemMetadataRepairTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ICON = "https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png";
    private static final String OLD_ICON = "https://render.guildwars2.com/file/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB/63133.png";
    private static final String A = "10000000-0000-0000-0000-00000000000a";
    private static final String B = "20000000-0000-0000-0000-00000000000b";
    private static final Instant COMPLETED = Instant.parse("2026-11-18T00:00:00Z");
    private final String schema = "test_account_metadata_" + UUID.randomUUID().toString().replace("-", "");
    private final List<List<Integer>> calls = new ArrayList<>();
    private Connection con;
    private Connection observer;

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(schema.matches("test_account_metadata_[0-9a-f]{32}"));
        observer = connect();
        exec("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        observer.setSchema(schema);
        con = connect();
        con.setSchema(schema);
        exec("INSERT INTO account_registry (account_id) VALUES ('" + A + "'), ('" + B + "')");
        exec("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES"
                + " ('" + A + "',0,31,7), ('" + B + "',0,31,70)");
        exec("INSERT INTO account_materials (account_id, item_id, count) VALUES"
                + " ('" + A + "',32,11), ('" + B + "',32,110)");
        exec("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                + " ('" + A + "','BANK',3,'2026-11-18T00:00:00Z'), ('" + A + "','MATERIALS',4,'2026-11-18T00:00:00Z'),"
                + " ('" + B + "','BANK',8,'2026-11-18T00:00:00Z'), ('" + B + "','MATERIALS',9,'2026-11-18T00:00:00Z')");
        assertAccountsUnchanged();
    }

    @AfterEach
    void tearDown() throws Exception {
        Thread.interrupted();
        try {
            if (con != null) con.close();
        } finally {
            if (observer != null) {
                try {
                    assertTrue(schema.matches("test_account_metadata_[0-9a-f]{32}"), "drop only the owned generated schema");
                    exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                } finally { observer.close(); }
            }
        }
    }

    @Test
    void repairsMissingFlagsFiltersMixedRowsAndRetriesUnknownOrOmittedFacts() throws Exception {
        assertTrue(IconSync.repairMissingMetadata(con, Arrays.asList(null, null), ids -> {
            throw new AssertionError("empty wanted input must not fetch");
        }).isEmpty());
        exec("INSERT INTO items (item_id,name,type,rarity,vendor_value,icon_url,no_mystic_forge) VALUES"
                + " (10,'Warm item','Trophy','Fine',12,'" + ICON + "',false),"
                + " (11,'Retained name','Trophy','Fine',5,'" + ICON + "',NULL),"
                + " (16,'Bad stored source','Trophy','Fine',12,'https://example.invalid/icon.png',false)");
        var input = Arrays.asList(16, 10, 11, 12, 13, 14, 15, 11, null);

        Set<Integer> failed = IconSync.repairMissingMetadata(con, input, ids -> {
            calls.add(List.copyOf(ids));
            return array(metadata(0, "Invalid id", "[]"), metadata(9999, "Not requested", "[]"),
                    metadata(11, "Upstream name", "[]"), metadata(12, "First accepted", "[\"NoMysticForge\"]"),
                    metadata(12, "Duplicate must not win", "[]"), metadata(13, "Unknown flags", "\"malformed\""),
                    metadata(14, "Malformed member", "[5]"), metadata(16, "Other upstream name", "[]"));
        });

        assertEquals(Set.of(), failed, "ordinary omissions and unknown flags are not failed requests");
        assertEquals(List.of(List.of(11, 12, 13, 14, 15, 16)), calls);
        assertEquals(List.of(complete(10, "Warm item", false),
                new ItemFacts(11, "Retained name", "Trophy", "Fine", 5, ICON, null, false),
                complete(12, "First accepted", true), complete(13, "Unknown flags", null),
                complete(14, "Malformed member", null), complete(16, "Bad stored source", false)), facts());
        assertNull(fact(15));
        assertNull(fact(9999));
        assertTrue(IconSync.repairMissingMetadata(con, List.of(10, 11, 12, 16), ids -> {
            throw new AssertionError("complete canonical fields and explicit flags must be warm");
        }).isEmpty());

        assertTrue(IconSync.repairMissingMetadata(con, input, ids -> {
            calls.add(List.copyOf(ids));
            return array(metadata(13, "Ignored replacement name", "[]"), metadata(14, "Ignored replacement name", "[]"),
                    metadata(15, "Previously omitted", "[\"NoMysticForge\"]"));
        }).isEmpty());
        assertEquals(List.of(List.of(11, 12, 13, 14, 15, 16), List.of(13, 14, 15)), calls);
        assertEquals(complete(13, "Unknown flags", false), fact(13));
        assertEquals(complete(14, "Malformed member", false), fact(14));
        assertEquals(complete(15, "Previously omitted", true), fact(15));
        assertAccountsUnchanged();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lateFlagUpdateFailureRollsBackWholeBatchRestoresConnectionAndAllowsRetry(boolean originalAutoCommit) throws Exception {
        exec("INSERT INTO items (item_id,name,vendor_value,icon_url,icon_path,fetched_at) VALUES"
                + " (31,'Last good name',5,'" + OLD_ICON + "','cached-old.png','2026-11-18T00:00:00Z')");
        exec("CREATE FUNCTION reject_late_flag() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'controlled late flag failure' USING ERRCODE='P0001'; END; $$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER reject_late_flag BEFORE UPDATE OF no_mystic_forge ON items"
                + " FOR EACH ROW WHEN (NEW.item_id=32) EXECUTE FUNCTION reject_late_flag()");
        con.setAutoCommit(originalAutoCommit);
        IconSync.ItemMetadataSource source = ids -> {
            calls.add(List.copyOf(ids));
            return array(metadata(31, "Replacement must not replace a known name", "[]"),
                    metadata(32, "New shared item", "[\"NoMysticForge\"]"));
        };

        assertEquals(Set.of(31, 32), IconSync.repairMissingMetadata(con, List.of(32, 31), source),
                "a late SQL failure reports the whole affected request batch");

        assertEquals(originalAutoCommit, con.getAutoCommit());
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT 7")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1), "the same connection remains usable after rollback");
        }
        assertEquals(List.of(new ItemFacts(31, "Last good name", null, null, 5, OLD_ICON, "cached-old.png", null)), facts(),
                "ordinary upserts and the earlier flag update roll back with the late flag failure");
        assertEquals(COMPLETED, fetchedAt(31));
        assertAccountsUnchanged();
        exec("DROP TRIGGER reject_late_flag ON items");

        assertTrue(IconSync.repairMissingMetadata(con, List.of(32, 31), source).isEmpty());

        assertEquals(originalAutoCommit, con.getAutoCommit());
        assertEquals(List.of(List.of(31, 32), List.of(31, 32)), calls);
        assertEquals(List.of(new ItemFacts(31, "Last good name", "Trophy", "Fine", 5, ICON, "cached-old.png", false),
                complete(32, "New shared item", true)), facts());
        assertEquals(COMPLETED, fetchedAt(31), "completion leaves the retained row's fetched timestamp alone");
        assertAccountsUnchanged();
    }

    @Test
    void failedFirstAccountBatchDoesNotBlockLaterBatchAndRetryFetchesOnlyIncompleteIds() throws Exception {
        var input = new ArrayList<>(ids(1001, 1201));
        input.add(1001);
        input.add(null);
        java.util.Collections.reverse(input);

        Set<Integer> failed = IconSync.repairMissingMetadata(con, input, batch -> {
            calls.add(List.copyOf(batch));
            if (batch.getFirst() == 1001) throw new IOException("controlled first-batch outage");
            return metadataFor(batch);
        });

        assertEquals(new LinkedHashSet<>(ids(1001, 1200)), failed);
        assertEquals(List.of(ids(1001, 1200), List.of(1201)), calls, "sorted deduplicated 200+1 account requests");
        assertEquals(List.of(complete(1201, "Item 1201", false)), facts());

        assertTrue(IconSync.repairMissingMetadata(con, input, batch -> {
            calls.add(List.copyOf(batch));
            return metadataFor(batch);
        }).isEmpty());

        assertEquals(List.of(ids(1001, 1200), List.of(1201), ids(1001, 1200)), calls);
        assertEquals(ids(1001, 1201), facts().stream().map(ItemFacts::id).toList());
        assertEquals(201, count("SELECT count(*) FROM items WHERE name='Item ' || item_id"
                + " AND type='Trophy' AND rarity='Fine' AND vendor_value=12 AND icon_url='" + ICON + "' AND no_mystic_forge=false"));
        assertAccountsUnchanged();
    }

    @Test
    void interruptionPreservesEarlierBatchStopsLaterFetchAndCanBeExplicitlyRetried() throws Exception {
        var interrupted = new InterruptedException("controlled account metadata cancellation");
        try {
            var thrown = assertThrows(InterruptedException.class, () -> IconSync.repairMissingMetadata(con,
                    ids(2001, 2401), batch -> {
                        calls.add(List.copyOf(batch));
                        if (batch.getFirst() == 2201) throw interrupted;
                        return metadataFor(batch);
                    }));
            assertSame(interrupted, thrown);
            assertTrue(Thread.currentThread().isInterrupted(), "repair restores the interrupted status before propagating");
        } finally { Thread.interrupted(); }

        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(List.of(ids(2001, 2200), ids(2201, 2400)), calls, "no fetch of the third batch after interruption");
        assertEquals(ids(2001, 2200), facts().stream().map(ItemFacts::id).toList(),
                "earlier committed batch survives; interrupted and later ids have no partial metadata");
        assertAccountsUnchanged();

        assertTrue(IconSync.repairMissingMetadata(con, ids(2001, 2401), batch -> {
            calls.add(List.copyOf(batch));
            return metadataFor(batch);
        }).isEmpty());
        assertEquals(List.of(ids(2001, 2200), ids(2201, 2400), ids(2201, 2400), List.of(2401)), calls);
        assertEquals(ids(2001, 2401), facts().stream().map(ItemFacts::id).toList());
        assertAccountsUnchanged();
    }

    private static List<Integer> ids(int first, int last) { return IntStream.rangeClosed(first, last).boxed().toList(); }

    private static JsonNode metadata(int id, String name, String flags) throws IOException {
        var row = JSON.createObjectNode().put("id", id).put("name", name).put("type", "Trophy")
                .put("rarity", "Fine").put("vendor_value", 12).put("icon", ICON);
        row.set("flags", JSON.readTree(flags));
        return row;
    }

    private static ArrayNode array(JsonNode... rows) {
        var result = JSON.createArrayNode();
        for (JsonNode row : rows) result.add(row);
        return result;
    }

    private static JsonNode metadataFor(List<Integer> ids) throws IOException {
        var result = JSON.createArrayNode();
        for (int id : ids) result.add(metadata(id, "Item " + id, "[]"));
        return result;
    }

    private record ItemFacts(int id, String name, String type, String rarity, Integer vendor,
                             String icon, String path, Boolean noMysticForge) {}

    private static ItemFacts complete(int id, String name, Boolean noMysticForge) {
        return new ItemFacts(id, name, "Trophy", "Fine", 12, ICON, null, noMysticForge);
    }

    private List<ItemFacts> facts() throws SQLException {
        var rows = new ArrayList<ItemFacts>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT item_id,name,type,rarity,vendor_value,icon_url,icon_path,no_mystic_forge FROM items ORDER BY item_id")) {
            while (rs.next()) rows.add(new ItemFacts(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, Integer.class), rs.getString(6), rs.getString(7), rs.getObject(8, Boolean.class)));
        }
        return rows;
    }

    private ItemFacts fact(int id) throws SQLException { return facts().stream().filter(row -> row.id() == id).findFirst().orElse(null); }

    private Instant fetchedAt(int id) throws SQLException {
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery("SELECT fetched_at FROM items WHERE item_id=" + id)) {
            assertTrue(rs.next());
            return rs.getTimestamp(1).toInstant();
        }
    }

    private record Stock(String source, UUID account, int slot, int item, int count) {}
    private record Marker(UUID account, String source, long generation, Instant completed) {}

    private void assertAccountsUnchanged() throws SQLException {
        var stock = new ArrayList<Stock>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT 'BANK',account_id,slot,item_id,count FROM account_bank
                UNION ALL SELECT 'MATERIALS',account_id,0,item_id,count FROM account_materials ORDER BY 1,2,3,4
                """)) {
            while (rs.next()) stock.add(new Stock(rs.getString(1), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
        }
        assertEquals(List.of(new Stock("BANK", UUID.fromString(A), 0, 31, 7), new Stock("BANK", UUID.fromString(B), 0, 31, 70),
                new Stock("MATERIALS", UUID.fromString(A), 0, 32, 11), new Stock("MATERIALS", UUID.fromString(B), 0, 32, 110)), stock);
        var markers = new ArrayList<Marker>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT account_id,source,generation,completed_at FROM account_source_state ORDER BY account_id,source")) {
            while (rs.next()) markers.add(new Marker(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant()));
        }
        assertEquals(List.of(new Marker(UUID.fromString(A), "BANK", 3, COMPLETED), new Marker(UUID.fromString(A), "MATERIALS", 4, COMPLETED),
                new Marker(UUID.fromString(B), "BANK", 8, COMPLETED), new Marker(UUID.fromString(B), "MATERIALS", 9, COMPLETED)), markers);
        assertEquals(2, count("SELECT count(*) FROM account_registry"));
    }

    private long count(String sql) throws SQLException {
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private void exec(String sql) throws SQLException { try (Statement s = observer.createStatement()) { s.execute(sql); } }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
    }
}
