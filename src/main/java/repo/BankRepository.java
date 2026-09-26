package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Slot-level read of the account bank for the Bank view's display (STORY-APP-009). Holds the query
 * and connection lifecycle that {@code BankView} previously ran itself against a hardcoded inline
 * {@code DriverManager} connection (docs/KNOWN_PROBLEMS.md §2.2); the SQL, its {@code items} join,
 * its {@code slot} ordering and its nullable columns are unchanged from that pre-extraction
 * version.
 *
 * <p>Separate from {@link InventoryRepository}, which also reads {@code account_bank} but for a
 * different purpose and with different semantics: that one aggregates owned quantity per item
 * across bank/materials/character inventories and classifies rows by binding, so it can neither
 * report empty slots nor carry the per-slot icon/rarity this view renders.
 */
public class BankRepository {

    /**
     * One {@code account_bank} slot exactly as the query returns it. {@code itemId}/{@code count}
     * are null for an empty slot; {@code iconPath}/{@code iconUrl}/{@code rarity} are null when the
     * slot's item has no matching {@code items} row (LEFT JOIN).
     *
     * <p>{@code iconPath} is the local desktop file JavaFX renders; {@code iconUrl} is the retained
     * upstream icon source ({@code items.icon_url}) a browser-facing read derives its image URL from
     * (TARGET_ARCHITECTURE.md §12.1). Both come from this one batch read, so neither costs a per-row
     * lookup.
     */
    public record BankSlotRow(int slot,
                              Integer itemId,
                              Integer count,
                              String iconPath,
                              String iconUrl,
                              String rarity) {}

    /** Reads every bank slot using the shared {@link Db#open()} connection configuration. */
    public List<BankSlotRow> loadBankSlots() throws SQLException {
        try (Connection con = Db.open()) {
            return loadBankSlots(con);
        }
    }

    /**
     * Same query as {@link #loadBankSlots()}, but runs against a caller-supplied connection so a
     * repository integration test can point it at a disposable test schema
     * (docs/TEST_STRATEGY.md §31.2), mirroring {@link InventoryRepository}'s existing pattern.
     */
    public List<BankSlotRow> loadBankSlots(Connection con) throws SQLException {
        String sql = """
            SELECT b.slot, b.item_id, b.count, i.icon_path, i.icon_url, i.rarity
            FROM account_bank b
            LEFT JOIN items i ON i.item_id = b.item_id
            ORDER BY b.slot
        """;

        List<BankSlotRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int slot = rs.getInt("slot");
                Integer itemId  = (Integer) rs.getObject("item_id");
                Integer count   = (Integer) rs.getObject("count");
                String iconPath = rs.getString("icon_path");
                String iconUrl  = rs.getString("icon_url");
                String rarity   = rs.getString("rarity");

                out.add(new BankSlotRow(slot, itemId, count, iconPath, iconUrl, rarity));
            }
        }
        return out;
    }
}
