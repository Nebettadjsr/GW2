package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Recognizes a database whose schema is owned by the versioned migration chain, and refuses the
 * legacy unscoped account paths on one (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.2-7.3).
 *
 * <p>Two rules meet here. Flyway is the sole schema writer for migrated objects, so the remaining
 * {@code CREATE TABLE IF NOT EXISTS} bootstraps must not run against a managed database. And no
 * unscoped runtime path may run against the upgraded account schema, because an account read or
 * write without a verified account GUID is exactly what account isolation forbids - the migrated
 * account tables keep their familiar names, so a legacy statement would otherwise still resolve.
 *
 * <p>On an unmigrated database nothing changes: the legacy bootstraps behave exactly as they did,
 * so the pre-cutover installation keeps working until the gate upgrades it.
 *
 * <p>Fails closed by design. The scoped account repositories that replace these paths exist as of
 * STORY-APP-015 and require an explicit {@code model.AccountScope}; what an upgraded database still
 * lacks is an HTTP boundary that resolves a session into one, so a retained unscoped entry point
 * reports account access as unavailable rather than guessing an account.
 *
 * <p>Failing closed is not the same as being unusable, which is what STORY-SYNC-007 separates: a
 * workflow that read shared and account data in one statement is split in two, so its global half
 * (shared items, recipes, the material catalog and the Trading Post quote cache) runs here with no
 * scope and is refused by nothing, and only the account half stays behind this gate. An unscoped
 * caller therefore contributes no account row rather than being stopped outright - and still
 * never acquires one by default, including when the account registry is empty.
 */
public final class ManagedSchema {

    /** Flyway's history table, the marker that the versioned chain owns this schema. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    private ManagedSchema() {}

    /**
     * Whether the connection's current schema is owned by the versioned chain.
     *
     * <p>A catalog read only, so it is safe inside a {@code REPEATABLE READ} snapshot that must not
     * become a writing transaction (see {@code application.ProfitInputSession}).
     */
    public static boolean isManaged(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1
                               FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_name = ?)
                """)) {
            ps.setString(1, HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Requires the versioned account schema for a scoped account path (STORY-APP-015).
     *
     * <p>The mirror image of {@link #rejectUnscopedAccountAccess}: together the two make the
     * boundary total, so neither half of the cutover can run against the other's schema. A scoped
     * statement names {@code account_id} columns and the {@code account_registry} foreign keys that
     * only exist from migration V2 on, so on a pre-migration database it would fail with
     * PostgreSQL's undefined-column error; this says what is actually wrong instead.
     *
     * <p>A catalog read only, like {@link #isManaged}, so a scoped read may call it inside the
     * {@code REPEATABLE READ} calculation snapshot without turning it into a writing transaction.
     *
     * @param what the scoped object or path being attempted, named in the diagnostic
     */
    public static void requireManagedAccountSchema(Connection con, String what) throws SQLException {
        if (isManaged(con)) return;
        throw new SQLException(
                "The scoped account path '" + what + "' requires the versioned account schema, which"
                        + " this database does not have: its account tables are the legacy unscoped"
                        + " ones, with no account GUID to scope by. Start the backend so the startup"
                        + " gate applies the versioned chain (an unmanaged schema is reset first, see"
                        + " docs/CURRENT_ARCHITECTURE.md, 'Versioned schema lifecycle and cache"
                        + " reset'), then re-synchronize the account's sources.");
    }

    /**
     * Rejects an unscoped legacy account path on a migrated database.
     *
     * @param what the legacy object or path being refused, named in the diagnostic
     */
    public static void rejectUnscopedAccountAccess(Connection con, String what) throws SQLException {
        if (!isManaged(con)) return;
        throw new SQLException(
                "This database is managed by the versioned schema chain, so the unscoped legacy path '"
                        + what + "' is disabled: Flyway is the only schema writer for migrated objects,"
                        + " and account data may only be read or written for a verified account GUID."
                        + " Use the account-scoped repository or publication path, which takes an"
                        + " explicit model.AccountScope. The previous unscoped cache was dropped by"
                        + " the schema reset, so every account source is missing until a validated"
                        + " credential drives a fresh synchronization. See"
                        + " docs/CURRENT_ARCHITECTURE.md, 'Versioned schema lifecycle and cache"
                        + " reset'.");
    }
}
