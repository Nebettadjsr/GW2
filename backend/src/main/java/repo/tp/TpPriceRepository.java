package repo.tp;

import craft.PriceQuote;
import sync.tp.TpPriceFreshness;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads Trading Post price rows and maps them to the independent {@code craft.PriceQuote} domain
 * type (STORY-DOM-017, TARGET_ARCHITECTURE.md section 10): this class is the persistence-to-domain
 * mapping boundary, so callers - including {@code craft.*} - never see JDBC-shaped types.
 */
public class TpPriceRepository {

    public record CacheStatus(long cachedItems, long staleItems, Timestamp newestFetchedAt) { }

    public CacheStatus cacheStatus() throws SQLException {
        String sql = "SELECT COUNT(*), COUNT(*) FILTER (WHERE " + TpPriceFreshness.STALE_SQL_PREDICATE
                + "), MAX(tp.fetched_at) FROM tp_prices tp";
        try (Connection con = repo.Db.open(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Trading Post cache status query returned no row");
            return new CacheStatus(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3));
        }
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
     * (docs/TEST_STRATEGY.md §31.2) without going through production connection config.
     */
    public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> itemIds) throws SQLException {
        Map<Integer, PriceQuote> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT item_id, buy_unit_price, sell_unit_price
            FROM tp_prices
            WHERE item_id = ANY(?)
        """;

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
