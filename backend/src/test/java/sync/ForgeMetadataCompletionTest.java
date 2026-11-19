package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge reference metadata completion on a disposable migrated schema with a fake item upstream (no network):
 * absent ids come from the full positive Forge id set (a fresh-quote id and a non-tradeable Stone included),
 * only incomplete rows are fetched, a failed batch is retried by the next call, and no ids means no call.
 */
class ForgeMetadataCompletionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String schema = "test_forge_meta_" + UUID.randomUUID().toString().replace("-", "");
    private final List<List<Integer>> calls = new ArrayList<>();
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(schema.matches("test_forge_meta_[0-9a-f]{32}"));
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement s = admin.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Statement s = admin.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        admin.close();
    }

    @Test
    void absentForgeIdsAreCompletedEvenWhenQuotedFreshOrNontradeable_andTheCompleteOneIsNotRefetched() throws Exception {
        // 19976 complete already; 19721 has a fresh quote but no items row; 20796 is a non-tradeable Stone, no row
        exec("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url, no_mystic_forge) VALUES"
                + " (19976, 'Done', 'Trophy', 'Fine', 1, 'https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/1.png', false)");
        Set<Integer> failed = IconSync.completeMissingMetadata(Set.of(19976, 19721, 20796, -5, 0), this::upstream);
        assertTrue(failed.isEmpty());
        assertEquals(List.of(List.of(19721, 20796)), calls, "one batched call, only the incomplete ids, no non-positive ids");
        assertEquals("Item 19721", name(19721));
        assertEquals("Item 20796", name(20796));
        assertEquals("Done", name(19976));
    }

    @Test
    void failedBatchStaysIncompleteAndTheNextCompletionRetriesIt() throws Exception {
        boolean[] fail = {true};
        IconSync.ItemMetadataSource source = ids -> {
            if (fail[0]) { calls.add(ids); throw new java.io.IOException("upstream down"); }
            return upstream(ids);
        };
        assertEquals(Set.of(19721), IconSync.completeMissingMetadata(Set.of(19721), source));
        assertEquals(null, name(19721));
        fail[0] = false;
        assertTrue(IconSync.completeMissingMetadata(Set.of(19721), source).isEmpty());
        assertEquals("Item 19721", name(19721));
        assertEquals(2, calls.size());
        // now complete: a third pass costs no upstream call
        IconSync.completeMissingMetadata(Set.of(19721), source);
        assertEquals(2, calls.size());
    }

    @Test
    void emptyForgeSetMakesNoUpstreamCall() throws Exception {
        assertTrue(IconSync.completeMissingMetadata(Set.of(), this::upstream).isEmpty());
        assertTrue(calls.isEmpty());
    }

    private JsonNode upstream(List<Integer> ids) {
        calls.add(new ArrayList<>(new TreeSet<>(ids)));
        ArrayNode out = JSON.createArrayNode();
        for (int id : ids) {
            out.add(JSON.createObjectNode().put("id", id).put("name", "Item " + id).put("type", "Trophy")
                    .put("rarity", "Fine").put("vendor_value", 3)
                    .put("icon", "https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/" + id + ".png")
                    .set("flags", JSON.createArrayNode()));
        }
        return out;
    }

    private String name(int id) throws SQLException {
        try (Statement s = admin.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM items WHERE item_id = " + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void exec(String sql) throws SQLException { try (Statement s = admin.createStatement()) { s.execute(sql); } }
}
