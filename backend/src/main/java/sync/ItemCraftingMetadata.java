package sync;

import parser.ItemParser;
import repo.ManagedSchema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Retains explicit public item flags alongside the ordinary metadata batch. */
final class ItemCraftingMetadata {
    private ItemCraftingMetadata() {}

    static void store(Connection con, List<ItemParser.ItemRow> rows) throws SQLException {
        if (rows.stream().noneMatch(row -> row.noMysticForge() != null) || !ManagedSchema.isManaged(con)) return;
        try (var ps = con.prepareStatement("""
                UPDATE items SET no_mystic_forge = ?
                WHERE item_id = ? AND no_mystic_forge IS DISTINCT FROM ?
                """)) {
            for (var row : rows) {
                if (row.noMysticForge() == null) continue;
                ps.setBoolean(1, row.noMysticForge());
                ps.setInt(2, row.itemId());
                ps.setBoolean(3, row.noMysticForge());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
