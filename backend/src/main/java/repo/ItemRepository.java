package repo;

import java.sql.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ItemRepository {

    public static class ItemInfo {
        public final int itemId;
        public final String name;

        /**
         * Retained upstream icon metadata ({@code items.icon_url}), null when none is stored. Carried
         * by the same batch read as the rest of the display metadata so a presentation layer can derive
         * an icon URL per item without a second, per-row lookup. It is a
         * source string, not a URL type, and it stays out of every domain calculation.
         */
        public final String iconUrl;

        /** Vendor sell value in copper; 0 when unknown or none (DOMAIN_SPEC.md 11.2 step 2). */
        public final int vendorValue;

        public ItemInfo(int itemId, String name, String iconUrl) {
            this(itemId, name, iconUrl, 0);
        }

        /** API item type (Weapon/Armor/CraftingMaterial...) and base subtype (LongBow/Coat...); null when not retained. */
        public final String type;
        public final String subtype;
        public final String rarity;
        public final String weightClass;

        public ItemInfo(int itemId, String name, String iconUrl, int vendorValue) {
            this(itemId, name, iconUrl, vendorValue, null, null);
        }

        public ItemInfo(int itemId, String name, String iconUrl, int vendorValue, String type, String subtype) {
            this(itemId, name, iconUrl, vendorValue, type, subtype, null, null);
        }

        public ItemInfo(int itemId, String name, String iconUrl, int vendorValue, String type, String subtype,
                        String rarity, String weightClass) {
            this.type = type;
            this.subtype = subtype;
            this.rarity = rarity;
            this.weightClass = weightClass;
            this.vendorValue = vendorValue;
            this.itemId = itemId;
            this.name = name;
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

    /** Positive ids whose stored row is missing/incomplete under the canonical item metadata policy (one short read). */
    public Set<Integer> incompleteMetadataIds(Set<Integer> itemIds) throws SQLException {
        try (Connection con = Db.open()) {
            return incompleteIds(con, itemIds);
        }
    }

    /** The positive ids of {@code itemIds} whose stored metadata row is missing or incomplete (read-only, one short query). */
    public static Set<Integer> incompleteIds(Connection con, Collection<Integer> itemIds) throws SQLException {
        Set<Integer> wanted = new java.util.TreeSet<>();
        for (Integer id : itemIds) if (id != null && id > 0) wanted.add(id);
        if (wanted.isEmpty()) return wanted;
        wanted.removeAll(completeMetadataIds(con, wanted));
        return wanted;
    }

    /** The subset of {@code wanted} whose stored row already carries every canonical metadata field and an accepted icon. */
    public static Set<Integer> completeMetadataIds(Connection con, Collection<Integer> wanted) throws SQLException {
        Set<Integer> complete = new java.util.HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT item_id, icon_url FROM items WHERE item_id = ANY(?) AND icon_url IS NOT NULL"
                        + " AND btrim(coalesce(name, '')) <> '' AND btrim(coalesce(type, '')) <> ''"
                        + " AND btrim(coalesce(rarity, '')) <> '' AND no_mystic_forge IS NOT NULL"
                        + " AND (type NOT IN ('Weapon', 'Armor') OR btrim(coalesce(subtype, '')) <> '')")) {
            ps.setArray(1, con.createArrayOf("int", wanted.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (domain.icons.IconSourcePolicy.accept(rs.getString(2)).isPresent()) complete.add(rs.getInt(1));
                }
            }
        }
        return complete;
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
     * (readers must not mix input generations).
     */
    public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> itemIds) throws SQLException {
        Map<Integer, ItemInfo> out = new HashMap<>();
        if (itemIds.isEmpty()) return out;

        String sql = """
            SELECT i.item_id, i.name, i.icon_url, i.vendor_value, i.type,
                   COALESCE(NULLIF(i.subtype, ''), d.details->>'subtype') AS subtype, i.rarity,
                   d.details->>'weightClass' AS weight_class
            FROM items i LEFT JOIN equipment_item_details d ON d.item_id = i.item_id
            WHERE i.item_id = ANY(?)
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            Array arr = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, arr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("item_id");
                    String name = rs.getString("name");
                    String iconUrl = rs.getString("icon_url");
                    out.put(id, new ItemInfo(id, name, iconUrl, rs.getInt("vendor_value"),
                            rs.getString("type"), rs.getString("subtype"), rs.getString("rarity"),
                            rs.getString("weight_class")));
                }
            }
        }

        return out;
    }
}
