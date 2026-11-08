package sync;

import api.BatchUtils;
import api.TaskDeadline;
import api.tp.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import parser.TpPriceParser;
import repo.Db;
import repo.ManagedSchema;
import sync.tp.relevance.CraftingProfitItemCollector;
import sync.tp.relevance.DiscoveryItemCollector;
import util.DbBind;
import util.TpPrice;

import java.sql.*;
import java.util.*;

public final class TpSync {

    private TpSync() {}

    /** Test seam: the Trading Post upstream. Production delegates to {@link TpPriceApi}. */
    interface TpUpstream {
        TpPriceApi.BatchResult fetchBatch(List<Integer> batch) throws Exception;
        JsonNode fetchSingle(int itemId) throws Exception;
        Set<Integer> fetchAllTradeableItemIds() throws Exception;

        /** Deadline-aware variants used by the full-catalog operation; fakes may keep the plain ones. */
        default TpPriceApi.BatchResult fetchBatch(List<Integer> batch, TaskDeadline deadline) throws Exception {
            return fetchBatch(batch);
        }
        default JsonNode fetchSingle(int itemId, TaskDeadline deadline) throws Exception {
            return fetchSingle(itemId);
        }
    }

    static volatile TpUpstream upstream = new TpUpstream() {
        @Override public TpPriceApi.BatchResult fetchBatch(List<Integer> batch) throws Exception {
            return TpPriceApi.fetchBatch(batch);
        }
        @Override public JsonNode fetchSingle(int itemId) throws Exception {
            return TpPriceApi.fetchSingle(itemId);
        }
        @Override public TpPriceApi.BatchResult fetchBatch(List<Integer> batch, TaskDeadline deadline) throws Exception {
            return TpPriceApi.fetchBatch(batch, deadline);
        }
        @Override public JsonNode fetchSingle(int itemId, TaskDeadline deadline) throws Exception {
            return TpPriceApi.fetchSingle(itemId, deadline);
        }
        @Override public Set<Integer> fetchAllTradeableItemIds() throws Exception {
            return TpPriceApi.fetchAllTradeableItemIds();
        }
    };

    public static void syncTpPrices(Set<Integer> itemIds) throws Exception {
        syncTpPrices(itemIds, TaskDeadline.NONE);
    }

    /**
     * Owner full Trading Post synchronization (STORY-STATUS-004): snapshots every ID in
     * {@code tp_tradeable_items}, bypassing the ordinary ten-minute freshness selection, through the
     * same shared {@code tp_prices} write path. Fails (never silently succeeds) on an empty catalog.
     */
    public static void syncAllTradeablePrices(TaskDeadline deadline) throws Exception {
        Set<Integer> ids = new HashSet<>();
        deadline.check();
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                deadline.limitTransaction(con);
                try (PreparedStatement ps = con.prepareStatement("SELECT item_id FROM tp_tradeable_items");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getInt(1));
                }
                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
        deadline.check();
        if (ids.isEmpty()) {
            throw new IllegalStateException("tp_tradeable_items is empty; synchronize the tradeable catalog first");
        }
        syncTpPrices(ids, deadline, true);
    }

    static void syncTpPrices(Set<Integer> itemIds, TaskDeadline deadline) throws Exception {
        syncTpPrices(itemIds, deadline, false);
    }

    private static void syncTpPrices(Set<Integer> itemIds, TaskDeadline deadline, boolean strict) throws Exception {

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

        int done = 0;

        // One DB connection for the whole sync
        try (Connection con = Db.open()) {

            con.setAutoCommit(false);

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                deadline.check();
                // Observation time = just before this batch is fetched. Publication is monotonic per
                // item (see upsertTpPrices), so a delayed older observation of an overlapping ID can
                // never replace a newer quote or move fetched_at backwards, whichever run finishes last.
                Timestamp runTs = new Timestamp(System.currentTimeMillis());
                List<TpPrice> quotes = strict ? fetchTpBatchStrict(batch, deadline) : fetchTpBatchParsed(batch, deadline);

                try {
                    deadline.check();
                    deadline.limitTransaction(con);
                    upsertTpPrices(con, quotes, runTs);
                    deadline.check();
                    con.commit();
                    deadline.check();

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

    /**
     * Upserts a batch of already-fetched {@link TpPrice} quotes into {@code tp_prices}, writing an
     * explicit all-NULL row (via {@link DbBind#setLongOrNull}/{@link DbBind#setIntOrNull}) for quotes
     * without market data rather than coercing to {@code 0}. Extracted from {@link #syncTpPrices}
     * so the write path is callable independently of the live GW2 fetch in tests.
     */
    static void upsertTpPrices(Connection con, List<TpPrice> quotes, Timestamp runTs) throws SQLException {

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
        WHERE tp_prices.fetched_at IS NULL OR tp_prices.fetched_at <= EXCLUDED.fetched_at
        """;

        try (PreparedStatement ps = con.prepareStatement(upsertSql)) {

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
        }
    }


    private static List<TpPrice> fetchTpBatchParsed(List<Integer> batch, TaskDeadline deadline)
            throws Exception {

        TpPriceApi.BatchResult br = upstream.fetchBatch(batch, deadline);

        List<TpPrice> out = new ArrayList<>();

        if (br.statusCode() == 404) {

            for (int id : batch) {

                JsonNode one = upstream.fetchSingle(id, deadline);
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

                JsonNode one = upstream.fetchSingle(id, deadline);
                if (one == null) continue;

                TpPrice q = TpPriceParser.parse(one);
                if (q != null) out.add(q);
            }
        }

        return out;
    }


    /**
     * Full-operation fetch: every requested ID ends resolved (a validated quote) or explicitly
     * unavailable (404 batch or single fallback, stored as an all-null "no market data" row so the old
     * quote is not kept as if refreshed). A 200 omission, duplicate, unexpected ID or malformed quote
     * fails the run.
     */
    private static List<TpPrice> fetchTpBatchStrict(List<Integer> batch, TaskDeadline deadline) throws Exception {
        TpPriceApi.BatchResult br = upstream.fetchBatch(batch, deadline);
        Set<Integer> wanted = new HashSet<>(batch);
        Map<Integer, TpPrice> resolved = new HashMap<>();
        int status = br.statusCode();
        if (status != 404) {
            if (br.array() == null || !br.array().isArray()) {
                throw new IllegalStateException("tp batch is not an array");
            }
            for (JsonNode p : br.array()) {
                TpPrice q = strictQuote(p);
                if (!wanted.contains(q.itemId())) throw new IllegalStateException("tp batch contains an unexpected id");
                if (resolved.put(q.itemId(), q) != null) throw new IllegalStateException("tp batch contains a duplicate id");
            }
            if (status == 200 && resolved.size() != wanted.size()) {
                throw new IllegalStateException("tp batch omitted requested ids");
            }
        }
        for (int id : batch) {
            if (resolved.containsKey(id)) continue;
            deadline.check();
            JsonNode one = upstream.fetchSingle(id, deadline);
            if (one == null) {
                resolved.put(id, TpPrice.noData(id));
                continue;
            }
            TpPrice q = strictQuote(one);
            if (q.itemId() != id) throw new IllegalStateException("tp single returned a different id");
            resolved.put(id, q);
        }
        List<TpPrice> out = new ArrayList<>(batch.size());
        for (int id : batch) out.add(resolved.get(id));
        return out;
    }

    /** A quote needs a positive id and either both null sides or both well-formed non-negative sides. */
    private static TpPrice strictQuote(JsonNode p) {
        if (p == null || !p.isObject() || !p.has("id") || !p.get("id").isInt() || p.get("id").asInt() <= 0) {
            throw new IllegalStateException("tp quote is malformed");
        }
        // Both side fields must be present: an explicit JSON null is the supported "no market data"
        // shape, but an absent field (e.g. a bare {"id":1}) is a malformed quote.
        if (!p.has("buys") || !p.has("sells")) throw new IllegalStateException("tp quote is malformed");
        JsonNode buys = p.get("buys");
        JsonNode sells = p.get("sells");
        boolean noBuys = buys.isNull();
        boolean noSells = sells.isNull();
        if (noBuys && noSells) return TpPrice.noData(p.get("id").asInt());
        if (!validSide(buys) || !validSide(sells)) throw new IllegalStateException("tp quote is malformed");
        return new TpPrice(p.get("id").asInt(), buys.get("quantity").asLong(), buys.get("unit_price").asInt(),
                sells.get("quantity").asLong(), sells.get("unit_price").asInt());
    }

    private static boolean validSide(JsonNode side) {
        return side != null && side.isObject()
                && side.path("quantity").canConvertToLong() && side.path("quantity").isIntegralNumber()
                && side.get("quantity").asLong() >= 0
                && side.path("unit_price").isInt() && side.get("unit_price").asInt() >= 0;
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

    /**
     * The Profit price refresh: fetch the stale or missing quotes the Profit selection requires.
     *
     * <p>Carries no account scope, so on the versioned database it asks for the global selection
     * (STORY-SYNC-007): the shared crafting graph and the shared quote cache, with no account row
     * entering the demand and no account inferred. On a pre-cutover database the selection derived
     * from the stored recipe knowledge is unchanged.
     *
     * <p>Required-ID-only either way - both selections apply the same ten-minute freshness filter,
     * so this refetches nothing a previous refresh already covered, and neither variant walks the
     * Trading Post catalog.
     */
    public static void syncTpPricesForProfit() throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.open()) {
            CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
            itemIds = ManagedSchema.isManaged(con) ? collector.collectGlobal(con) : collector.collect(con);
        }
        syncTpPrices(itemIds);
    }

    /**
     * The Profit price refresh for one authorized account's calculation (STORY-API-011): the stale or
     * missing quotes that account's required item set needs, into the same shared quote cache and
     * under the same ten-minute policy as the global selection.
     */
    public static void syncTpPricesForProfit(model.AccountScope scope) throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.open()) {
            itemIds = new CraftingProfitItemCollector().collect(con, scope);
        }
        syncTpPrices(itemIds);
    }

    /** The Discovery counterpart of {@link #syncTpPricesForProfit(model.AccountScope)}. */
    public static void syncTpPricesForDiscovery(model.AccountScope scope) throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.open()) {
            itemIds = new DiscoveryItemCollector().collect(con, scope);
        }
        syncTpPrices(itemIds);
    }

    /** The Discovery price refresh; globally selected without a scope exactly as {@link #syncTpPricesForProfit()}. */
    public static void syncTpPricesForDiscovery() throws Exception {
        Set<Integer> itemIds;
        try (Connection con = Db.open()) {
            DiscoveryItemCollector collector = new DiscoveryItemCollector();
            itemIds = ManagedSchema.isManaged(con) ? collector.collectGlobal(con) : collector.collect(con);
        }
        syncTpPrices(itemIds);
    }

    public static boolean syncTpTradeableItems() throws Exception {
        System.out.println("Fetching TP tradeable id list...");
        Set<Integer> ids = upstream.fetchAllTradeableItemIds();
        System.out.println("Tradeable TP ids: " + ids.size());

        Set<Integer> local = new HashSet<>();
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement("SELECT item_id FROM tp_tradeable_items"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) local.add(rs.getInt(1));
        }
        IdListDiff difference = IdListDiff.between(local, ids);
        if (!difference.changed()) return false;
        Set<Integer> addedIds = difference.added();
        ItemSync.syncItemsByIds(addedIds);

        String insertSql = """
        INSERT INTO tp_tradeable_items (item_id, fetched_at)
        VALUES (?, now())
        ON CONFLICT (item_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
        """;

        try (Connection con = Db.open();
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
        return true;
    }
}
