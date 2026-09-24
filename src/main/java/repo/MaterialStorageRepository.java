package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stack-level read of account material storage for the Materials view's display (STORY-APP-009).
 * Holds the query and connection lifecycle that {@code MaterialsView} previously ran itself against
 * a hardcoded inline {@code DriverManager} connection (docs/KNOWN_PROBLEMS.md §2.2); the SQL, its
 * {@code items} join, its {@code count IS NOT NULL AND count > 0} filter and its
 * {@code category, item_id} ordering are unchanged from that pre-extraction version.
 *
 * <p>Separate from {@link InventoryRepository} for the same reason as {@link BankRepository}: that
 * repository sums {@code account_materials} into an account-wide owned pool and drops the category
 * and icon/rarity columns this view groups and renders by.
 */
public class MaterialStorageRepository {

    /**
     * One non-empty {@code account_materials} stack exactly as the query returns it.
     * {@code iconPath}/{@code rarity} are null when the stack's item has no matching {@code items}
     * row (LEFT JOIN). {@code itemId} carries the query's secondary ordering key; the Materials
     * view does not display it.
     */
    public record MaterialStorageRow(int category, Integer itemId, int count, String iconPath, String rarity) {}

    /** Reads every non-empty material stack using the shared {@link Db#open()} connection configuration. */
    public List<MaterialStorageRow> loadMaterialStorage() throws SQLException {
        try (Connection con = Db.open()) {
            return loadMaterialStorage(con);
        }
    }

    /**
     * Same query as {@link #loadMaterialStorage()}, but runs against a caller-supplied connection so
     * a repository integration test can point it at a disposable test schema
     * (docs/TEST_STRATEGY.md §31.2), mirroring {@link InventoryRepository}'s existing pattern.
     */
    public List<MaterialStorageRow> loadMaterialStorage(Connection con) throws SQLException {
        String sql = """
            SELECT am.category, am.item_id, am.count,
                   i.icon_path, i.rarity
            FROM account_materials am
            LEFT JOIN items i ON i.item_id = am.item_id
            WHERE am.count IS NOT NULL AND am.count > 0
            ORDER BY am.category, am.item_id
        """;

        List<MaterialStorageRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int category = rs.getInt("category");
                Integer itemId = (Integer) rs.getObject("item_id");
                int count = rs.getInt("count");
                String iconPath = rs.getString("icon_path");
                String rarity = rs.getString("rarity");

                out.add(new MaterialStorageRow(category, itemId, count, iconPath, rarity));
            }
        }
        return out;
    }
}
