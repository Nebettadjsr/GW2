package repo.tp;

import craft.MaterialTradeability;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads the Trading Post <em>tradeability classification</em> of items and maps it to the domain type
 * {@link MaterialTradeability} (TARGET_ARCHITECTURE.md section 10: this class is the
 * persistence-to-domain boundary, so {@code craft.*} never sees JDBC-shaped types).
 *
 * <p>The source is {@code tp_tradeable_items}, the item-id list global synchronization stores from the
 * Trading Post's own tradeable-item listing (CURRENT_STATE_SPEC.md section on that table). It is a
 * classification, not price data: {@code tp_prices} is a different table read by
 * {@link TpPriceRepository}, and an item present here with no usable quote stays
 * {@code PRICE_UNAVAILABLE} (DOMAIN_SPEC.md sections 2.1.1 and 21). Nothing here consults quotes,
 * icons or any other display metadata.
 *
 * <p>An item the classification does not list is not tradeable through the Trading Post. That makes
 * the answer only as complete as the last global synchronization: with {@code tp_tradeable_items}
 * never synchronized, no item is listed and every item is reported as non-Trading-Post. That is what
 * the stored data says rather than an assumption, and it surfaces as visibly blocked results instead
 * of silently permitting a path (DOMAIN_SPEC.md section 2.1.1's "unknown or unavailable facts must
 * not be silently converted into zero or success").
 */
public class TpTradeableItemRepository {

    /**
     * Which of {@code itemIds} are not tradeable on the Trading Post, as a domain classification.
     * Returns {@link MaterialTradeability#noneKnown()} for an empty request.
     */
    public MaterialTradeability loadTradeability(Set<Integer> itemIds) throws SQLException {
        if (itemIds.isEmpty()) return MaterialTradeability.noneKnown();

        try (Connection con = repo.Db.open()) {
            return loadTradeability(con, itemIds);
        }
    }

    /**
     * Same query as {@link #loadTradeability(Set)}, but on a caller-supplied connection, so a
     * repository integration test can point it at a disposable test schema (docs/TEST_STRATEGY.md
     * section 31.2) instead of production connection config.
     */
    public MaterialTradeability loadTradeability(Connection con, Set<Integer> itemIds) throws SQLException {
        if (itemIds.isEmpty()) return MaterialTradeability.noneKnown();

        String sql = """
            SELECT item_id
            FROM tp_tradeable_items
            WHERE item_id = ANY(?)
        """;

        Set<Integer> nonTradeable = new HashSet<>(itemIds);

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nonTradeable.remove(rs.getInt("item_id"));
                }
            }
        }

        return MaterialTradeability.ofNonTradeableItems(nonTradeable);
    }
}
