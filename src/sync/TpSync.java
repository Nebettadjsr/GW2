package sync;

import api.BatchUtils;
import api.tp.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import parser.TpPriceParser;
import sync.tp.relevance.CraftingProfitItemCollector;
import sync.tp.relevance.DiscoveryItemCollector;
import util.DbBind;
import util.TpPrice;

import java.sql.*;
import java.util.*;

public final class TpSync {

    private TpSync() {}

    public static void syncTpPrices(Set<Integer> itemIds) throws Exception {

        // NOTE (TP refresh scope):
        // This method intentionally refreshes TP prices only for items relevant to the current view
        // (profit or discovery). Prices are not refreshed continuously and may be several minutes old.
        // This is acceptable because GW2 TP prices change relatively slowly and manual refresh is used.
        //
        // time limit optimization:
        // We exclude items whose tp_prices.fetched_at is newer than 10 minutes to avoid refetching
        // recently synced prices. This significantly reduces API calls when refresh is triggered
        // multiple times in a short period.

        List<Integer> ids = new ArrayList<>(itemIds);
        Collections.sort(ids);

        System.out.println("TP price items to fetch: " + ids.size());
        if (ids.isEmpty()) return;

        // 2) Upsert SQL
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

        // 3) One DB connection for the whole sync
        try (Connection con = Db.openConnection();
             PreparedStatement ps = con.prepareStatement(upsertSql)) {

            con.setAutoCommit(false);

            // one timestamp for the whole run (no SELECT now() spam)
            Timestamp runTs = new Timestamp(System.currentTimeMillis());

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                List<TpPrice> quotes = fetchTpBatchParsed(batch);

                try {
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

    public static void syncTpPricesForProfit() throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.openConnection()) {
            itemIds = new CraftingProfitItemCollector().collect(con);
        }
        syncTpPrices(itemIds);
    }

    public static void syncTpPricesForDiscovery() throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.openConnection()) {
            itemIds = new DiscoveryItemCollector().collect(con);
        }
        syncTpPrices(itemIds);
    }

    public static void syncTpTradeableItems() throws Exception {
        System.out.println("Fetching TP tradeable id list...");
        Set<Integer> ids = TpPriceApi.fetchAllTradeableItemIds();
        System.out.println("Tradeable TP ids: " + ids.size());

        String insertSql = """
        INSERT INTO tp_tradeable_items (item_id, fetched_at)
        VALUES (?, now())
        ON CONFLICT (item_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
        """;

        try (Connection con = Db.openConnection();
             PreparedStatement ps = con.prepareStatement(insertSql)) {

            con.setAutoCommit(false);

            // simplest + robust: wipe + reinsert (keeps table exact)
            try (Statement st = con.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE tp_tradeable_items");
            }

            int i = 0;
            for (int id : ids) {
                ps.setInt(1, id);
                ps.addBatch();

                if (++i % 2000 == 0) ps.executeBatch();
            }

            ps.executeBatch();
            con.commit();
        }

        System.out.println("✅ tp_tradeable_items refreshed.");
    }
}