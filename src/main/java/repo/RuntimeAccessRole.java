package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The restricted PostgreSQL role every runtime connection switches to once a database holds a
 * preserved legacy archive (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.3-7.4).
 *
 * <p>The archive cannot be protected by grants alone: the configured runtime credential is also the
 * owner of the archived tables, and an owner keeps access to what it owns even after every
 * {@code PUBLIC} grant is removed and the archive is excluded from the search path. The only
 * boundary that actually denies a schema-qualified read is a different, privilege-less role, so
 * {@link #restrict(Connection)} - called by {@link Db#open()}, the single runtime database access
 * boundary - performs {@code SET ROLE} to this role. Runtime statements then run with
 * {@code current_user} set to a role that holds data privileges on the live schema only and nothing
 * at all on the archive, while the session's own credential stays the owner for the migration and
 * archive paths that must reconcile the preserved rows.
 *
 * <p>The role is named after the live schema it belongs to, so a disposable test schema gets its own
 * role and the production schema keeps one stable role. It exists only where an archive exists:
 * the adoption path creates it in the same transaction that creates the archive, which is
 * why "an archive is present" and "runtime connections are restricted" cannot come apart. An
 * installation that never held legacy data has no archive, no role and therefore unchanged runtime
 * behavior.
 *
 * <p>The adoption path is {@code infra.schema.LegacySchemaAdoption}; it creates the role, grants it
 * the live schema and revokes the archive from it, and the startup gate re-applies the live grants
 * after it applies pending migrations.
 */
public final class RuntimeAccessRole {

    /** Prefix of the per-live-schema role name. */
    public static final String ROLE_PREFIX = "gw2_runtime_";

    /** PostgreSQL identifier limit (NAMEDATALEN - 1). */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private RuntimeAccessRole() {}

    /** The restricted role belonging to one live schema. */
    public static String roleNameFor(String liveSchema) {
        String name = ROLE_PREFIX + liveSchema;
        return name.length() <= MAX_IDENTIFIER_LENGTH ? name : name.substring(0, MAX_IDENTIFIER_LENGTH);
    }

    public static boolean exists(Connection con, String role) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Switches this connection to the restricted role when the database has one, which is exactly
     * when it holds a preserved archive.
     *
     * @return whether the connection is now restricted
     */
    public static boolean restrict(Connection con) throws SQLException {
        String schema = currentSchema(con);
        if (schema == null) return false;
        String role = roleNameFor(schema);
        if (!exists(con, role)) return false;
        try (Statement st = con.createStatement()) {
            // Must run at statement level: SET ROLE inside a function or DO block is reverted when
            // the block returns, which would leave the connection unrestricted.
            st.execute("SET ROLE " + quote(role));
        }
        return true;
    }

    /**
     * Creates the restricted role for one live schema if it does not exist yet, and makes the
     * current credential a member so a non-superuser owner can switch to it. The role is created
     * without login and without privilege inheritance: it is reachable only through {@code SET ROLE}
     * and grants nothing implicitly.
     */
    public static void create(Connection con, String liveSchema) throws SQLException {
        String role = roleNameFor(liveSchema == null ? currentSchema(con) : liveSchema);
        try (Statement st = con.createStatement()) {
            st.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                            CREATE ROLE %s NOLOGIN NOINHERIT;
                        END IF;
                    END $$
                    """.formatted(role, quote(role)));
            st.execute("GRANT " + quote(role) + " TO CURRENT_USER");
        }
    }

    /**
     * Grants the restricted role the data privileges the runtime needs on one live schema, and
     * nothing more: no {@code CREATE}, so it cannot write schema, and no privilege outside this
     * schema. Idempotent, and a no-op when no restricted role exists. The default privileges cover
     * objects a later migration adds, and the gate re-runs this after it applies pending versions.
     */
    public static void grantLiveSchemaAccess(Connection con, String liveSchema) throws SQLException {
        String schema = liveSchema == null ? currentSchema(con) : liveSchema;
        String role = roleNameFor(schema);
        if (!exists(con, role)) return;
        try (Statement st = con.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA " + quote(schema) + " TO " + quote(role));
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                    + quote(schema) + " TO " + quote(role));
            st.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA "
                    + quote(schema) + " TO " + quote(role));
            st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + quote(schema)
                    + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + quote(role));
            st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + quote(schema)
                    + " GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + quote(role));
        }
    }

    /**
     * Removes every privilege the restricted role could have on one schema and its tables. Used for
     * the archive schema, so the runtime role cannot resolve, let alone read, a preserved table even
     * when it is named with its schema.
     */
    public static void denyAccessTo(Connection con, String liveSchema, String deniedSchema) throws SQLException {
        String role = roleNameFor(liveSchema == null ? currentSchema(con) : liveSchema);
        if (!exists(con, role)) return;
        try (Statement st = con.createStatement()) {
            st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + quote(deniedSchema) + " FROM " + quote(role));
            st.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA " + quote(deniedSchema) + " FROM " + quote(role));
            st.execute("REVOKE ALL ON SCHEMA " + quote(deniedSchema) + " FROM " + quote(role));
        }
    }

    public static String currentSchema(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT current_schema()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Quotes an identifier this class composes into DDL, which PreparedStatement cannot parameterize. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
