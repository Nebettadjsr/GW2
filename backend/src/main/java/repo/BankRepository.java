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

    /**
     * One account's bank slots, ordered by slot (STORY-APP-015).
     *
     * <p>The same query as the unscoped {@link #loadBankSlots(Connection)} with the owning account
     * added to the {@code WHERE} clause: slots are keyed {@code (account_id, slot)}, so two accounts
     * both holding slot 3 are two rows and only this account's is read. The {@code items} join stays
     * global - item metadata and icons are shared data, not account data.
     */
    public List<BankSlotRow> loadBankSlots(model.AccountScope scope) throws SQLException {
        try (Connection con = Db.open()) {
            return loadBankSlots(con, scope);
        }
    }

    /** @see #loadBankSlots(model.AccountScope) */
    public List<BankSlotRow> loadBankSlots(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "account_bank");
        String sql = """
            SELECT b.slot, b.item_id, b.count, i.icon_path, i.icon_url, i.rarity
            FROM account_bank b
            LEFT JOIN items i ON i.item_id = b.item_id
            WHERE b.account_id = ?
            ORDER BY b.slot
        """;

        List<BankSlotRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rowOf(rs));
            }
        }
        return out;
    }

    /**
     * Reads every bank slot using the shared {@link Db#open()} connection configuration.
     *
     * <p>The entry point for a caller that holds no {@link model.AccountScope}, which on the
     * versioned database means it has no account to read: it returns no rows rather than reading
     * across accounts (STORY-SYNC-007). Nothing is inferred - not a configured, last-used or only
     * registered account - and no row of any account is exposed, so a global workflow that merely
     * passes an account input along (the item-icon read path) stays usable while scope resolution is
     * introduced by its own story.
     *
     * <p>An empty result here is the absence of a scope, <em>not</em> a claim that this account's
     * bank is empty or that its synchronization succeeded: completion stays recorded per source in
     * {@code account_source_state}, where no row means the source is missing. The scoped
     * {@link #loadBankSlots(model.AccountScope)} is the only path that reports an account's actual
     * slots.
     *
     * <p>On a pre-cutover database nothing changes: the unscoped query below is this installation's
     * only bank read and keeps returning its rows.
     */
    public List<BankSlotRow> loadBankSlots() throws SQLException {
        try (Connection con = Db.open()) {
            if (ManagedSchema.isManaged(con)) return List.of();
            return loadBankSlots(con);
        }
    }

    /**
     * Same query as {@link #loadBankSlots()}, but runs against a caller-supplied connection so a
     * repository integration test can point it at a disposable test schema
     * (docs/TEST_STRATEGY.md §31.2), mirroring {@link InventoryRepository}'s existing pattern.
     */
    public List<BankSlotRow> loadBankSlots(Connection con) throws SQLException {
        // Reads every account's bank slots on a migrated database, which account isolation forbids
        // (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.2). The scoped replacement arrives with the
        // account-access stories; until then this is unavailable rather than cross-account.
        ManagedSchema.rejectUnscopedAccountAccess(con, "account_bank without an account scope");
        String sql = """
            SELECT b.slot, b.item_id, b.count, i.icon_path, i.icon_url, i.rarity
            FROM account_bank b
            LEFT JOIN items i ON i.item_id = b.item_id
            ORDER BY b.slot
        """;

        List<BankSlotRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) out.add(rowOf(rs));
        }
        return out;
    }

    private static BankSlotRow rowOf(ResultSet rs) throws SQLException {
        return new BankSlotRow(
                rs.getInt("slot"),
                (Integer) rs.getObject("item_id"),
                (Integer) rs.getObject("count"),
                rs.getString("icon_path"),
                rs.getString("icon_url"),
                rs.getString("rarity"));
    }
}
