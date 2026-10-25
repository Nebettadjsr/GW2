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

        /** Vendor sell value in copper; 0 when unknown or none (DOMAIN_SPEC.md 11.2 step 2). */
        public final int vendorValue;

        public ItemInfo(int itemId, String name, String iconPath, String iconUrl) {
            this(itemId, name, iconPath, iconUrl, 0);
        }

        public ItemInfo(int itemId, String name, String iconPath, String iconUrl, int vendorValue) {
            this.vendorValue = vendorValue;
            this.itemId = itemId;
            this.name = name;
            this.iconPath = iconPath;
            this.iconUrl = iconUrl;
        }
    }

    /** Positive vendor sell values by item id, for the domain's untradeable-material fallback. */
    public static Map<Integer, Integer> vendorValues(Map<Integer, ItemInfo> items) {
        Map<Integer, Integer> out = new HashMap<>();
        for (ItemInfo info : items.values()) {
            if (info.vendorValue > 0) out.put(info.itemId, info.vendorValue);
        }
        return out;
    }

    public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) throws SQLException {
        if (itemIds.isEmpty()) return new HashMap<>();
        try (Connection con = repo.Db.open()) {
            return loadItems(con, itemIds);
        }
    }

    /**
     * Same query as {@link #loadItems(Set)}, but on a caller-supplied connection, so one
     * calculation can read every input it needs inside a single consistent database snapshot
     * (TARGET_ARCHITECTURE.md §9.1: readers must not mix input generations).
     */
    public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> itemIds) throws SQLException {
        Map<Integer, ItemInfo> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT item_id, name, icon_path, icon_url, vendor_value
            FROM items
            WHERE item_id = ANY(?)
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("item_id");
                    String name = rs.getString("name");
                    String iconPath = rs.getString("icon_path");
                    String iconUrl = rs.getString("icon_url");
                    out.put(id, new ItemInfo(id, name, iconPath, iconUrl, rs.getInt("vendor_value")));
                }
            }
        }

        return out;
    }
}
