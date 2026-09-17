package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import parser.ItemParser;
import util.DbBind;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ItemSync {

    private ItemSync() {}

    public static void syncItemsByIds(Set<Integer> itemIds) throws Exception {

        if (itemIds == null || itemIds.isEmpty()) return;

        String itemSql = """
        INSERT INTO items (item_id, name, type, rarity, vendor_value, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (item_id) DO UPDATE SET
          name = EXCLUDED.name,
          type = EXCLUDED.type,
          rarity = EXCLUDED.rarity,
          vendor_value = EXCLUDED.vendor_value,
          fetched_at = EXCLUDED.fetched_at
        """;

        List<Integer> idsList = new ArrayList<>(itemIds);

        int done = 0;

        try (Connection con = Db.openConnection();
             PreparedStatement ps = con.prepareStatement(itemSql);
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {

            for (List<Integer> batch : BatchUtils.chunk(idsList, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

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

                    ps.clearBatch();

                    for (var r : rows) {

                        ps.setInt(1, r.itemId());
                        DbBind.setStringOrNull(ps, 2, r.name());
                        DbBind.setStringOrNull(ps, 3, r.type());
                        DbBind.setStringOrNull(ps, 4, r.rarity());

                        ps.setInt(5, r.vendorValue() == null ? 0 : r.vendorValue());
                        ps.setTimestamp(6, runTs);

                        ps.addBatch();
                    }

                    ps.executeBatch();
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
}