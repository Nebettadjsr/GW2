package sync;

import parser.ItemParser;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Retains explicit public item flags alongside the ordinary metadata batch. */
final class ItemCraftingMetadata {
    private ItemCraftingMetadata() {}

    static void store(Connection con, List<ItemParser.ItemRow> rows) throws SQLException {
        storeSubtypes(con, rows);
        if (rows.stream().noneMatch(row -> row.noMysticForge() != null)) return;
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

    /** Base weapon/armor subtype ({@code details.type}) retained so Forge search never guesses it from names. */
    private static void storeSubtypes(Connection con, List<ItemParser.ItemRow> rows) throws SQLException {
        if (rows.stream().noneMatch(row -> row.subtype() != null)) return;
        try (var ps = con.prepareStatement("""
                UPDATE items SET subtype = ?
                WHERE item_id = ? AND subtype IS DISTINCT FROM ?
                """)) {
            for (var row : rows) {
                if (row.subtype() == null) continue;
                ps.setString(1, row.subtype());
                ps.setInt(2, row.itemId());
                ps.setString(3, row.subtype());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
