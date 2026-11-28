package repo.tp;

import craft.PriceQuote;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads Trading Post price rows and maps them to the independent {@code craft.PriceQuote} domain
 * type: this class is the persistence-to-domain
 * mapping boundary, so callers - including {@code craft.*} - never see JDBC-shaped types.
 */
public class TpPriceRepository {

    public record CacheStatus(long cachedItems, long staleItems, Timestamp newestFetchedAt) { }

    public CacheStatus cacheStatus() throws SQLException {
        String sql = "SELECT COUNT(*), COUNT(*) FILTER (WHERE " + TpPriceFreshnessSql.STALE_PREDICATE
                + "), MAX(tp.fetched_at) FROM tp_prices tp";
        try (Connection con = repo.Db.open(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Trading Post cache status query returned no row");
            return new CacheStatus(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3));
        }
    }

    /**
     * The tradeable ids among {@code itemIds} whose quote is missing or older than the shared ten-minute policy
     * (the same explicit coverage definition a refresh fetches by). Non-tradeable ids are never reported, so they
     * cannot demand a refresh forever. The connection is closed before this returns.
     */
    public Set<Integer> staleOrMissingTradeable(Set<Integer> itemIds) throws Exception {
        try (Connection con = repo.Db.open()) {
            return new sync.tp.relevance.CraftingProfitItemCollector().collectExplicit(con, itemIds);
        }
    }

    /**
     * Quotes only for items in the CURRENT tradeable catalogue: a retained {@code tp_prices} row of a retired item is
     * not a current ask, whatever its age. Scoped to the Mystic Forge surfaces; ordinary quote loading is unchanged.
     */
    public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Set<Integer> itemIds) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadCurrentTradeableQuotes(con, itemIds);
        }
    }

    public Map<Integer, PriceQuote> loadCurrentTradeableQuotes(Connection con, Set<Integer> itemIds) throws SQLException {
        return query(con, itemIds, """
            SELECT p.item_id, p.buy_unit_price, p.sell_unit_price
            FROM tp_prices p
            JOIN tp_tradeable_items t ON t.item_id = p.item_id
            WHERE p.item_id = ANY(?)
        """);
    }

    public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadTpQuotes(con, itemIds);
        }
    }

    /**
     * Same query as {@link #loadTpQuotes(Set)}, but runs against a caller-supplied
     * connection instead of opening one via {@link repo.Db#open()}. Exists so repository
     * integration tests can point this query at a disposable test schema/database
     * without going through production connection config.
     */
    public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> itemIds) throws SQLException {
        return query(con, itemIds, """
            SELECT item_id, buy_unit_price, sell_unit_price
            FROM tp_prices
            WHERE item_id = ANY(?)
        """);
    }

    private static Map<Integer, PriceQuote> query(Connection con, Set<Integer> itemIds, String sql) throws SQLException {
        Map<Integer, PriceQuote> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("item_id");
                    Integer buy = (Integer) rs.getObject("buy_unit_price");
                    Integer sell = (Integer) rs.getObject("sell_unit_price");
                    out.put(id, new PriceQuote(buy, sell));
                }
            }
        }

        return out;
    }
}
