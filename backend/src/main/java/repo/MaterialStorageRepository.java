package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Ordered material-catalog positions combined with the last synchronized account snapshot. */
public class MaterialStorageRepository {
    public record MaterialStorageRow(int category, String categoryName, int categoryOrder,
                                     Integer position, Integer itemId, int count,
                                     String iconPath, String iconUrl, String rarity) {
        public MaterialStorageRow(int category, String categoryName, int categoryOrder,
                                  Integer position, Integer itemId, int count, String iconUrl, String rarity) {
            this(category, categoryName, categoryOrder, position, itemId, count, null, iconUrl, rarity);
        }
        public MaterialStorageRow(int category, Integer itemId, int count, String iconPath,
                                  String iconUrl, String rarity) {
            this(category, "Category " + category, category, 0, itemId, count, iconPath, iconUrl, rarity);
        }
    }

    public List<MaterialStorageRow> loadMaterialStorage() throws SQLException {
        try (Connection con = Db.open()) { return loadMaterialStorage(con); }
    }

    /**
     * One account's material storage over the shared ordered catalog (STORY-APP-015).
     *
     * <p>Two changes from the unscoped {@link #loadMaterialStorage(Connection)}. The
     * {@code account_materials} join carries the owning account, so a quantity is this account's or
     * zero, never another account's. And the "has this been synchronized at all?" question is
     * answered by this account's {@code MATERIALS} marker in {@code account_source_state} instead of
     * the singleton {@code account_materials_sync} row, which is retired as a runtime authority
     * (TARGET_ARCHITECTURE.md §7.2): that row said only that <em>somebody</em> had synchronized.
     *
     * <p>The catalog itself ({@code material_categories}, {@code material_category_items}) and item
     * metadata stay global and unjoined to any account, so both accounts read the same positions and
     * icons. Reading creates no marker: a missing marker is reported as unsynchronized, never
     * written.
     */
    public List<MaterialStorageRow> loadMaterialStorage(model.AccountScope scope) throws SQLException {
        try (Connection con = Db.open()) { return loadMaterialStorage(con, scope); }
    }

    /** @see #loadMaterialStorage(model.AccountScope) */
    public List<MaterialStorageRow> loadMaterialStorage(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "material storage");
        if (new AccountSourceStateRepository()
                .read(con, scope, AccountSyncStateRepository.MATERIALS).isEmpty()) {
            throw new SQLException("Account material storage has not been synchronized");
        }
        String sql = """
                SELECT c.category_id, c.name, c.display_order, ci.position, ci.item_id,
                       CASE WHEN am.item_id IS NULL THEN 0 ELSE am.count END AS count,
                       i.icon_path, i.icon_url, i.rarity
                FROM material_categories c
                LEFT JOIN material_category_items ci ON ci.category_id = c.category_id
                LEFT JOIN account_materials am ON am.item_id = ci.item_id AND am.account_id = ?
                LEFT JOIN items i ON i.item_id = ci.item_id
                ORDER BY c.display_order, c.category_id, ci.position
                """;
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            statement.setObject(1, scope.accountId());
            try (ResultSet rs = statement.executeQuery()) {
                return readRows(rs);
            }
        }
    }

    public List<MaterialStorageRow> loadMaterialStorage(Connection con) throws SQLException {
        // The join below reads account_materials without an account GUID, and the singleton
        // account_materials_sync marker no longer exists on a migrated database (STORY-INFRA-004).
        ManagedSchema.rejectUnscopedAccountAccess(con, "material storage without an account scope");
        MaterialStorageSchema.ensure(con);
        try (PreparedStatement sync = con.prepareStatement("SELECT fetched_at FROM account_materials_sync WHERE id = 1");
             ResultSet synced = sync.executeQuery()) {
            if (!synced.next()) throw new SQLException("Account material storage has not been synchronized");
        }
        String sql = """
                SELECT c.category_id, c.name, c.display_order, ci.position, ci.item_id,
                       CASE WHEN am.item_id IS NULL THEN 0 ELSE am.count END AS count,
                       i.icon_path, i.icon_url, i.rarity
                FROM material_categories c
                LEFT JOIN material_category_items ci ON ci.category_id = c.category_id
                LEFT JOIN account_materials am ON am.item_id = ci.item_id
                LEFT JOIN items i ON i.item_id = ci.item_id
                ORDER BY c.display_order, c.category_id, ci.position
                """;
        try (PreparedStatement statement = con.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            return readRows(rs);
        }
    }

    private static List<MaterialStorageRow> readRows(ResultSet rs) throws SQLException {
        List<MaterialStorageRow> rows = new ArrayList<>();
        while (rs.next()) {
            int count = rs.getInt("count");
            if (rs.wasNull()) throw new SQLException("Synchronized material count is unavailable");
            int positionValue = rs.getInt("position");
            Integer position = rs.wasNull() ? null : positionValue;
            int itemValue = rs.getInt("item_id");
            Integer itemId = rs.wasNull() ? null : itemValue;
            rows.add(new MaterialStorageRow(rs.getInt("category_id"), rs.getString("name"),
                    rs.getInt("display_order"), position, itemId, count, rs.getString("icon_path"),
                    rs.getString("icon_url"), rs.getString("rarity")));
        }
        return rows;
    }
}
