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

        /**
         * Retained upstream icon metadata ({@code items.icon_url}), null when none is stored. Carried
         * by the same batch read as the rest of the display metadata so a presentation layer can derive
         * an icon URL per item without a second, per-row lookup (TARGET_ARCHITECTURE.md §12.1). It is a
         * source string, not a URL type, and it stays out of every domain calculation.
         */
        public final String iconUrl;

        public ItemInfo(int itemId, String name, String iconPath, String iconUrl) {
            this.itemId = itemId;
            this.name = name;
            this.iconPath = iconPath;
            this.iconUrl = iconUrl;
        }
    }

    public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) throws SQLException {
        Map<Integer, ItemInfo> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT item_id, name, icon_path, icon_url
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
                    String iconUrl = rs.getString("icon_url");
                    out.put(id, new ItemInfo(id, name, iconPath, iconUrl));
                }
            }
        }

        return out;
    }
}
