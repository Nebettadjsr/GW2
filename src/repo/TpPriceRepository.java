package repo;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TpPriceRepository {

    public static class TpQuote {
        public final Integer buyUnit;   // buy_unit_price (can be null)
        public final Integer sellUnit;  // sell_unit_price (can be null)
        public TpQuote(Integer buyUnit, Integer sellUnit) {
            this.buyUnit = buyUnit;
            this.sellUnit = sellUnit;
        }
    }

    public Map<Integer, TpQuote> loadTpQuotes(Set<Integer> itemIds) throws SQLException {
        Map<Integer, TpQuote> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT item_id, buy_unit_price, sell_unit_price
            FROM tp_prices
            WHERE item_id = ANY(?)
        """;

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql)) {

            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("item_id");
                    Integer buy = (Integer) rs.getObject("buy_unit_price");
                    Integer sell = (Integer) rs.getObject("sell_unit_price");
                    out.put(id, new TpQuote(buy, sell));
                }
            }
        }

        return out;
    }
}
