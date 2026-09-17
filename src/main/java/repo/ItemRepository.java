package repo;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ItemRepository {

    public static class ItemInfo {
        public final int itemId;
        public final String name;
        public final String iconPath;

        public ItemInfo(int itemId, String name, String iconPath) {
            this.itemId = itemId;
            this.name = name;
            this.iconPath = iconPath;
        }
    }

    public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) throws SQLException {
        Map<Integer, ItemInfo> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT item_id, name, icon_path
            FROM items
            WHERE item_id = ANY(?)
        """;

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql)) {

            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("item_id");
                    String name = rs.getString("name");
                    String iconPath = rs.getString("icon_path");
                    out.put(id, new ItemInfo(id, name, iconPath));
                }
            }
        }

        return out;
    }
}
