package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class InventoryRepository {

    /**
     * "use mats from Bank" checked:
     * - include account_bank items + account_materials
     * unchecked:
     * - only account_materials
     */
    public Map<Integer, Integer> loadInventory(boolean includeBank) throws SQLException {
        Map<Integer, Integer> inv = new HashMap<>();

        try (Connection con = repo.Db.open()) {

            // materials storage
            try (PreparedStatement ps = con.prepareStatement("""
                SELECT item_id, count
                FROM account_materials
                WHERE item_id IS NOT NULL
            """);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    int count = rs.getInt("count");
                    inv.merge(itemId, count, Integer::sum);
                }
            }

            if (includeBank) {
                try (PreparedStatement ps = con.prepareStatement("""
                    SELECT item_id, count
                    FROM account_bank
                    WHERE item_id IS NOT NULL
                """);
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        int count = rs.getInt("count");
                        inv.merge(itemId, count, Integer::sum);
                    }
                }
            }
        }

        return inv;
    }
}
