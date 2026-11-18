package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Recognizes a database whose schema is owned by the versioned migration chain (docs/ARCHITECTURE.md
 * 'Schema lifecycle').
 *
 * <p>Both supported launchers apply the Flyway chain before anything else, so the managed schema is
 * the only supported one. {@link #isManaged} remains for the few catalog-level checks that still
 * distinguish a migrated schema, and {@link #requireManagedAccountSchema} fails closed with an
 * actionable diagnostic when a scoped account path meets a database without the account GUID
 * columns, rather than guessing an account.
 *
 * <p>Global work (shared items, recipes, the material catalog and the Trading Post quote cache) needs
 * no account scope, and an empty account registry neither empties it nor makes it infer an account.
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
     * <p>A scoped
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
                        + " docs/ARCHITECTURE.md, 'Schema lifecycle'), then re-synchronize the account's sources.");
    }
}
