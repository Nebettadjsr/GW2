package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import api.BatchUtils;
import parser.EquipmentDetailsParser;
import repo.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Independently committed, globally shared cache of slim public equipment details and itemstats rows
 * (STORY-CHAR-014). Only ids with no stored row are requested, so a warm cache makes no upstream call.
 * No database connection is held during an HTTP request; each batch (at most
 * {@link SyncConstants#HTTP_IDS_BATCH} ids) is committed on its own, and a failed or malformed batch
 * never removes stored data.
 */
public final class EquipmentDetailsSync {
    private EquipmentDetailsSync() {}

    /** Public upstream edge, replaceable by a fixture. Both return the endpoint's JSON array. */
    public interface Source {
        JsonNode items(List<Integer> ids) throws Exception;
        JsonNode itemStats(List<Integer> ids) throws Exception;
    }

    /** No upstream access: used where equipment details are not wanted. */
    public static final Source NONE = new Source() {
        public JsonNode items(List<Integer> ids) { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(); }
        public JsonNode itemStats(List<Integer> ids) { return items(ids); }
    };

    /** The live public GW2 endpoints with the shared finite timeouts. */
    public static final Source LIVE = new Source() {
        public JsonNode items(List<Integer> ids) throws Exception { return fetch("items", ids); }
        public JsonNode itemStats(List<Integer> ids) throws Exception { return fetch("itemstats", ids); }
        private JsonNode fetch(String endpoint, List<Integer> ids) throws Exception {
            return BuildMetadataRepair.fetchLive(endpoint, ids.stream().map(String::valueOf).toList());
        }
    };

    /**
     * Brings the item details of {@code itemIds}, then the itemstats they and {@code statIds} refer to,
     * up to date. Nonfatal except interruption.
     */
    public static void repair(Collection<Integer> itemIds, Collection<Integer> statIds, Source source, Clock clock)
            throws InterruptedException {
        try {
            for (List<Integer> batch : BatchUtils.chunk(missingDetails(itemIds),
                    SyncConstants.HTTP_IDS_BATCH)) {
                JsonNode body = source.items(batch);
                Set<Integer> wanted = new LinkedHashSet<>(batch);
                List<ObjectNode> rows = new ArrayList<>();
                for (JsonNode n : body) {
                    ObjectNode d = EquipmentDetailsParser.item(n);
                    if (d != null && wanted.remove(d.get("itemId").asInt())) rows.add(d);
                }
                store("equipment_item_details", "item_id", "itemId", "details", rows, clock);
            }
            Set<Integer> stats = new LinkedHashSet<>(statIds);
            stats.addAll(referencedStatIds(itemIds));
            for (List<Integer> batch : BatchUtils.chunk(missing("equipment_item_stats", "stat_id", stats),
                    SyncConstants.HTTP_IDS_BATCH)) {
                JsonNode body = source.itemStats(batch);
                Set<Integer> wanted = new LinkedHashSet<>(batch);
                List<ObjectNode> rows = new ArrayList<>();
                for (JsonNode n : body) {
                    ObjectNode d = EquipmentDetailsParser.stat(n);
                    if (d != null && wanted.remove(d.get("statId").asInt())) rows.add(d);
                }
                store("equipment_item_stats", "stat_id", "statId", "attributes", rows, clock);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            System.out.println("Equipment details unavailable; retry on the next synchronization: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    /** Ids without a stored row, or whose stored row is incomplete (retried; the old row stays until replaced). */
    private static List<Integer> missingDetails(Collection<Integer> ids) throws Exception {
        List<Integer> want = new ArrayList<>(new LinkedHashSet<>(ids));
        if (want.isEmpty()) return List.of();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Set<Integer> have = new LinkedHashSet<>();
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement(
                "SELECT item_id, details::text FROM equipment_item_details WHERE item_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", want.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        if (EquipmentDetailsParser.complete(mapper.readTree(rs.getString(2)))) have.add(rs.getInt(1));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
                        // unreadable row counts as missing
                    }
                }
            }
        }
        want.removeAll(have);
        return want;
    }

    private static List<Integer> missing(String table, String column, Collection<Integer> ids) throws Exception {
        List<Integer> want = new ArrayList<>(new LinkedHashSet<>(ids));
        if (want.isEmpty()) return List.of();
        Set<Integer> have = new LinkedHashSet<>();
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE " + column + " = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", want.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) have.add(rs.getInt(1));
            }
        }
        want.removeAll(have);
        return want;
    }

    private static Set<Integer> referencedStatIds(Collection<Integer> itemIds) throws Exception {
        Set<Integer> out = new LinkedHashSet<>();
        if (itemIds.isEmpty()) return out;
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement(
                "SELECT DISTINCT (details->>'statId')::int FROM equipment_item_details "
                        + "WHERE item_id = ANY(?) AND details->>'statId' IS NOT NULL")) {
            ps.setArray(1, con.createArrayOf("integer", new ArrayList<>(new LinkedHashSet<>(itemIds)).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    private static void store(String table, String column, String idField, String valueColumn,
                              List<ObjectNode> rows, Clock clock) throws Exception {
        if (rows.isEmpty()) return;
        Timestamp now = Timestamp.from(clock.instant());
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + table + " (" + column + ", "
                    + valueColumn + ", fetched_at) VALUES (?, ?::jsonb, ?) ON CONFLICT (" + column
                    + ") DO UPDATE SET " + valueColumn + " = EXCLUDED." + valueColumn
                    + ", fetched_at = EXCLUDED.fetched_at")) {
                for (ObjectNode r : rows) {
                    ps.setInt(1, r.get(idField).asInt());
                    ps.setString(2, r.toString());
                    ps.setTimestamp(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
        }
    }

    /**
     * Full item refresh (STORY-STATUS-004): rewrites the slim projection of items that <em>already</em>
     * have an {@code equipment_item_details} row from the same validated {@code /v2/items} payload, on
     * the caller's transaction. Uncached items get no row, an unprojectable record leaves its old row,
     * and {@code equipment_item_stats} is never touched.
     */
    static void refreshCached(Connection con, JsonNode itemsPayload, Timestamp ts) throws java.sql.SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE equipment_item_details SET details = ?::jsonb, fetched_at = ? WHERE item_id = ?")) {
            for (JsonNode n : itemsPayload) {
                ObjectNode d = EquipmentDetailsParser.item(n);
                if (d == null) continue;
                ps.setString(1, d.toString());
                ps.setTimestamp(2, ts);
                ps.setInt(3, d.get("itemId").asInt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
