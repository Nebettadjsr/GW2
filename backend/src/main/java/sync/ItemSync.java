package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import api.TaskDeadline;
import com.fasterxml.jackson.databind.JsonNode;
import parser.ItemParser;
import repo.Db;
import util.DbBind;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ItemSync {

    private ItemSync() {}

    /** Test seam: upstream fetch. Production keeps the real HTTP client. */
    static volatile PublicArrayFetcher fetcher = Gw2ApiClient::getPublicArray;

    private static final String ITEM_SQL = """
        INSERT INTO items (item_id, name, type, rarity, vendor_value, fetched_at, icon_url)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (item_id) DO UPDATE SET
          name = EXCLUDED.name,
          type = EXCLUDED.type,
          rarity = EXCLUDED.rarity,
          vendor_value = EXCLUDED.vendor_value,
          fetched_at = EXCLUDED.fetched_at,
          icon_url = COALESCE(EXCLUDED.icon_url, items.icon_url)
        """;

    public static void syncItemsByIds(Set<Integer> itemIds) throws Exception {

        if (itemIds == null || itemIds.isEmpty()) return;

        List<Integer> idsList = new ArrayList<>(itemIds);

        int done = 0;

        try (Connection con = Db.open();
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {

            for (List<Integer> batch : BatchUtils.chunk(idsList, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
                JsonNode root = fetcher.get(url);

                List<ItemParser.ItemRow> rows = new ArrayList<>(root.size());

                for (JsonNode it : root) {
                    var row = ItemParser.parse(it);
                    if (row != null) rows.add(row);
                }

                con.setAutoCommit(false);

                try {

                    Timestamp runTs;

                    try (ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next())
                            throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }

                    upsertItems(con, rows, runTs);
                    con.commit();

                } catch (Exception ex) {

                    con.rollback();
                    throw ex;
                }

                done += batch.size();

                System.out.println("Items batch progress: "
                                           + Math.min(done, idsList.size())
                                           + " / "
                                           + idsList.size());
            }
        }
    }

    /**
     * Owner full item-catalog synchronization (STORY-STATUS-004): captures one finite
     * {@code /v2/items} ID snapshot, re-downloads the detail of <em>every</em> captured ID (unchanged
     * ones included) in 200-ID batches, and only after all batches validated and committed removes
     * rows absent from the snapshot that no {@code recipe_ingredients} row still references. A
     * referenced absent item stays as disposable referential metadata until a later run.
     */
    static void syncAllItemsFull(TaskDeadline deadline) throws Exception {
        deadline.check();
        List<Integer> ids = FullCatalogSupport.parseIdSnapshot(fetcher.get("https://api.guildwars2.com/v2/items"), "items");
        syncItemsStrict(ids, deadline);
        deadline.check();
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                deadline.limitTransaction(con);
                try (PreparedStatement ps = con.prepareStatement("""
                        DELETE FROM items i
                        WHERE NOT (i.item_id = ANY (?))
                          AND NOT EXISTS (SELECT 1 FROM recipe_ingredients r WHERE r.item_id = i.item_id)
                        """)) {
                    ps.setArray(1, con.createArrayOf("int4", ids.toArray(Integer[]::new)));
                    ps.executeUpdate();
                }
                deadline.check();
                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    /** Detail batches that must cover the requested IDs exactly; used by the full operations. */
    static void syncItemsStrict(java.util.Collection<Integer> itemIds, TaskDeadline deadline) throws Exception {
        List<Integer> idsList = new ArrayList<>(itemIds);
        if (idsList.isEmpty()) return;
        try (Connection con = Db.open();
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {
            for (List<Integer> batch : BatchUtils.chunk(idsList, SyncConstants.HTTP_IDS_BATCH)) {
                deadline.check();
                JsonNode root = fetcher.get("https://api.guildwars2.com/v2/items?ids=" + BatchUtils.idsParam(batch));
                deadline.check();
                List<ItemParser.ItemRow> rows =
                        FullCatalogSupport.parseExact(root, batch,
                                n -> ItemParser.parse(FullCatalogSupport.requireItem(n)), ItemParser.ItemRow::itemId, "items");
                con.setAutoCommit(false);
                try {
                    deadline.limitTransaction(con);
                    Timestamp runTs;
                    try (ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }
                    upsertItems(con, rows, runTs);
                    // Same validated payload: refresh only equipment projections that are already cached.
                    EquipmentDetailsSync.refreshCached(con, root, runTs);
                    deadline.check();
                    con.commit();
                    deadline.check();
                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }
            }
        }
    }

    /** Shared by normal recipe-item synchronization and its real-database regression checks. */
    static void upsertItems(Connection con, List<ItemParser.ItemRow> rows, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(ITEM_SQL)) {
            for (var row : rows) {
                ps.setInt(1, row.itemId());
                DbBind.setStringOrNull(ps, 2, row.name());
                DbBind.setStringOrNull(ps, 3, row.type());
                DbBind.setStringOrNull(ps, 4, row.rarity());
                ps.setInt(5, row.vendorValue() == null ? 0 : row.vendorValue());
                ps.setTimestamp(6, runTs);
                DbBind.setStringOrNull(ps, 7, row.iconUrl());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        ItemCraftingMetadata.store(con, rows);
    }
}
