package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Recognizes a database whose schema is owned by the versioned migration chain, and refuses the
 * legacy unscoped account paths on one (STORY-INFRA-004, TARGET_ARCHITECTURE.md 7.2-7.3).
 *
 * <p>Two rules meet here. Flyway is the sole schema writer for migrated objects, so the remaining
 * {@code CREATE TABLE IF NOT EXISTS} bootstraps must not run against a managed database. And no
 * unscoped runtime path may run against the upgraded account schema, because an account read or
 * write without a verified account GUID is exactly what account isolation forbids.
 *
 * <p>On an unmigrated database nothing changes: the legacy bootstraps behave exactly as they did, so
 * the pre-cutover installation keeps working until it is explicitly adopted.
 *
 * <p>Fails closed by design. The scoped account repositories that replace these paths arrive with the
 * follow-up account-access stories; until then an upgraded database serves global data and reports
 * account access as unavailable rather than guessing an account.
 */
public final class ManagedSchema {

    /** Flyway's history table, the marker that the versioned chain owns this schema. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    private ManagedSchema() {}

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
                        + " Account access through scoped repositories is not available yet; the"
                        + " preserved legacy dataset is in the restricted archive schema."
                        + " See docs/CURRENT_ARCHITECTURE.md section 8.1.");
    }
}
