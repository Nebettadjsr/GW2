package sync;

import api.BatchUtils;
import api.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import parser.TpPriceParser;
import util.DbBind;
import util.TpPrice;

import java.sql.*;
import java.util.*;

public final class TpSync {

    private TpSync() {}

    public static void syncTpPricesRelevant() throws Exception {

        Set<Integer> itemIds = new HashSet<>();

        try (Connection con = Db.openConnection();
             Statement st = con.createStatement()) {

            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT item_id FROM account_bank WHERE item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT item_id FROM account_materials WHERE item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT r.output_item_id FROM recipes r WHERE r.output_item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT ri.item_id FROM recipe_ingredients ri WHERE ri.item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }
        }

        List<Integer> ids = new ArrayList<>(itemIds);
        Collections.sort(ids);

        System.out.println("TP price items to fetch: " + ids.size());
        if (ids.isEmpty()) return;

        String upsertSql = """
            INSERT INTO tp_prices
            (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (item_id) DO UPDATE SET
              buy_quantity = EXCLUDED.buy_quantity,
              buy_unit_price = EXCLUDED.buy_unit_price,
              sell_quantity = EXCLUDED.sell_quantity,
              sell_unit_price = EXCLUDED.sell_unit_price,
              fetched_at = EXCLUDED.fetched_at
            """;

        int done = 0;

        try (Connection con = Db.openConnection();
             PreparedStatement ps = con.prepareStatement(upsertSql);
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                List<TpPrice> quotes = fetchTpBatchParsed(batch);

                con.setAutoCommit(false);

                try {

                    Timestamp runTs;

                    try (ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next())
                            throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }

                    ps.clearBatch();

                    for (TpPrice q : quotes) {

                        if (q == null) continue;

                        if (!q.hasMarketData()) {
                            upsertTpRow(ps, q.itemId(), null, null, null, null, runTs);
                        } else {
                            upsertTpRow(ps,
                                        q.itemId(),
                                        q.buyQty(),
                                        q.buyUnit(),
                                        q.sellQty(),
                                        q.sellUnit(),
                                        runTs);
                        }
                    }

                    ps.executeBatch();
                    con.commit();

                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }

                done += batch.size();

                System.out.println("Fetched TP progress: "
                                           + Math.min(done, ids.size())
                                           + " / "
                                           + ids.size());
            }
        }

        System.out.println("✅ TP prices synced.");
    }


    private static List<TpPrice> fetchTpBatchParsed(List<Integer> batch)
            throws Exception {

        TpPriceApi.BatchResult br = TpPriceApi.fetchBatch(batch);

        List<TpPrice> out = new ArrayList<>();

        if (br.statusCode() == 404) {

            for (int id : batch) {

                JsonNode one = TpPriceApi.fetchSingle(id);
                if (one == null) continue;

                TpPrice q = TpPriceParser.parse(one);
                if (q != null) out.add(q);
            }

            return out;
        }

        for (JsonNode p : br.array()) {

            TpPrice q = TpPriceParser.parse(p);
            if (q != null) out.add(q);
        }

        if (br.statusCode() == 206) {

            Set<Integer> returned = br.returnedIds();

            for (int id : batch) {

                if (returned.contains(id)) continue;

                JsonNode one = TpPriceApi.fetchSingle(id);
                if (one == null) continue;

                TpPrice q = TpPriceParser.parse(one);
                if (q != null) out.add(q);
            }
        }

        return out;
    }


    private static void upsertTpRow(
            PreparedStatement ps,
            int itemId,
            Long buyQty,
            Integer buyUnit,
            Long sellQty,
            Integer sellUnit,
            Timestamp runTs
                                   ) throws SQLException {

        ps.setInt(1, itemId);
        DbBind.setLongOrNull(ps, 2, buyQty);
        DbBind.setIntOrNull(ps, 3, buyUnit);
        DbBind.setLongOrNull(ps, 4, sellQty);
        DbBind.setIntOrNull(ps, 5, sellUnit);
        ps.setTimestamp(6, runTs);

        ps.addBatch();
    }
}