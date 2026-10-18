package infra.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import repo.AppConfig;
import repo.Db;
import repo.RuntimeAccessRole;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The versioned-schema initialization gate (TARGET_ARCHITECTURE.md 7.3, ADR-010).
 *
 * <p>Every retained runtime entry point calls {@link #runStartupGate()} before anything can reach a
 * repository, a scheduler or a background task, so the schema is validated and migrated first or the
 * process does not serve. Flyway is the only schema writer for the objects under
 * {@code db/migration}; automatic baselining, destructive clean and automatic checksum repair stay
 * off, so an existing non-empty database is never silently declared current and a changed applied
 * migration fails instead of being repaired.
 *
 * <p>Three database states are distinguished, and the distinction is the whole point of the gate:
 *
 * <ul>
 *   <li>{@link State#MANAGED} - a Flyway history table is present. The chain is validated and any
 *       pending versions are applied, then the end state is validated again. An invalid or failed
 *       history fails startup.</li>
 *   <li>{@link State#EMPTY} - nothing in the schema at all. The complete versioned chain runs, which
 *       is what makes a fresh installation converge with an adopted legacy database.</li>
 *   <li>{@link State#LEGACY_UNMANAGED} - the manually created pre-migration schema. Nothing is
 *       written and no baseline is recorded: adopting it is an explicit, reviewed operation
 *       ({@link LegacySchemaAdoption}) that needs stopped writers and a restorable backup, so the
 *       gate reports what to run and leaves the legacy runtime paths working on the legacy schema.
 *       Keeping cutover out of ordinary startup is deliberate.</li>
 * </ul>
 */
public final class SchemaGate {

    /** Where the production chain lives; see {@code src/main/resources/db/migration}. */
    public static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * The version a supported legacy database is baselined at. V1 is exactly the legacy schema's
     * global objects, so baselining there asserts "those already exist" and nothing else - never
     * that any account row is owned by anybody.
     */
    public static final String LEGACY_BASELINE_VERSION = "1";

    /** Flyway's own history table, and the marker this class uses to recognize a managed database. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    public enum State {
        /** Flyway already owns this schema. */
        MANAGED,
        /** No objects at all; the full chain was applied. */
        EMPTY,
        /** Pre-migration manual schema; untouched, awaiting explicit adoption. */
        LEGACY_UNMANAGED
    }

    /**
     * What the gate found and did. {@code appliedVersions} is empty for a database that was already
     * current and for an unmanaged legacy one, where nothing was applied at all.
     */
    public record Result(State state, List<String> appliedVersions, String diagnostics) {

        public boolean migrated() {
            return state != State.LEGACY_UNMANAGED;
        }
    }

    private SchemaGate() {}

    /**
     * Runs the gate against the configured database.
     *
     * <p>It honors {@link repo.Db#TEST_SCHEMA_PROPERTY} for the same reason {@code Db.open()} does:
     * a check that drives a retained entry point must gate and connect to the same disposable
     * schema, never the developer's own. The property is unset in normal use, so production startup
     * gates the connection's default schema exactly as before.
     */
    public static Result runStartupGate() {
        String testSchema = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        return run(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS,
                testSchema == null || testSchema.isBlank() ? null : testSchema);
    }

    /**
     * Runs the gate against one database/schema.
     *
     * @param schema the schema to manage, or {@code null} for the connection's default schema. A
     *               disposable schema is how isolated tests run the production chain without
     *               touching anybody's normal database content.
     */
    public static Result run(String url, String user, String password, String schema) {
        State state;
        List<String> unfinishedAdoptions;
        try (Connection con = DriverManager.getConnection(url, user, password)) {
            if (schema != null) con.setSchema(schema);
            state = inspect(con);
            unfinishedAdoptions = unreconciledAdoptions(con);
        } catch (SQLException e) {
            throw new SchemaGateException("Cannot inspect the database schema state at " + url, e);
        }

        // Before anything else, including before a managed database is accepted as current: an
        // adoption that got as far as committing its preserved archive but did not finish its
        // provenance association and integrity reconciliation must not be served. Flyway commits
        // the versioned chain in the middle of a cutover, so without this check a crash or a
        // reconciliation failure after that point would leave a managed database that ordinary
        // startup happily accepts (TARGET_ARCHITECTURE.md 7.3, acceptance criterion 6).
        if (!unfinishedAdoptions.isEmpty()) {
            throw new SchemaGateException(unfinishedAdoptionDiagnostics(url, schema, state, unfinishedAdoptions));
        }

        if (state == State.LEGACY_UNMANAGED) {
            return new Result(State.LEGACY_UNMANAGED, List.of(), legacyDiagnostics(url, schema));
        }

        Flyway flyway = flyway(url, user, password, schema);
        MigrateResult migrate;
        try {
            migrate = flyway.migrate();
            // Validate the end state as well: migrate() validates what was already applied, and this
            // second pass additionally proves nothing is left pending or out of order afterwards.
            flyway.validate();
        } catch (FlywayException e) {
            throw new SchemaGateException(migrationFailureDiagnostics(url, schema, state, e), e);
        }

        // Objects a pending version just created must be reachable for the restricted runtime role
        // of a database that holds a preserved archive; without an archive there is no such role
        // and this is a no-op.
        try (Connection con = DriverManager.getConnection(url, user, password)) {
            if (schema != null) con.setSchema(schema);
            RuntimeAccessRole.grantLiveSchemaAccess(con, schema);
        } catch (SQLException e) {
            throw new SchemaGateException("Cannot re-apply the restricted runtime role's live-schema"
                    + " privileges after migrating " + url, e);
        }

        List<String> applied = migrate.migrations == null
                ? List.of()
                : migrate.migrations.stream().map(m -> m.version + " " + m.description).toList();
        return new Result(state, applied, successDiagnostics(state, migrate.targetSchemaVersion, applied));
    }

    /**
     * The one Flyway configuration the gate and {@link LegacySchemaAdoption} share, so the baseline
     * an adoption records and the chain startup validates can never be configured differently.
     */
    static Flyway flyway(String url, String user, String password, String schema) {
        var configuration = Flyway.configure()
                .dataSource(url, user, password)
                .locations(MIGRATION_LOCATION)
                .failOnMissingLocations(true)
                // Never guess that an existing database is current - 7.3 and Flyway's own
                // baseline-on-migrate warning both require the explicit preflight instead.
                .baselineOnMigrate(false)
                .baselineVersion(LEGACY_BASELINE_VERSION)
                .baselineDescription("legacy global schema adopted by explicit preflight")
                // Destructive clean must not be reachable from the application at all.
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .outOfOrder(false)
                // One transaction per migration, and no DDL/DML mixing surprises.
                .mixed(false)
                .group(false);
        if (schema != null) configuration = configuration.schemas(schema);
        return configuration.load();
    }

    /** Classifies a schema without writing anything to it. */
    static State inspect(Connection con) throws SQLException {
        if (relationExists(con, HISTORY_TABLE)) return State.MANAGED;
        return countRelations(con) == 0 ? State.EMPTY : State.LEGACY_UNMANAGED;
    }

    static boolean relationExists(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1
                               FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_name = ?)
                """)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Every legacy adoption of this live schema that preserved its archive but never completed its
     * reconciliation, one description per unfinished import.
     *
     * <p>An archive is found by its own structure rather than by a configured name, because the
     * archive schema name is an adoption argument: a schema holding an {@code import_run} table with
     * the adoption's own columns is one, and only its imports for {@code current_schema()} concern
     * this startup. The archive commits before Flyway records the baseline, so this marker exists
     * for the whole window in which a cutover could be interrupted, and
     * {@code LegacySchemaAdoption} sets {@code reconciled_at} only in the transaction that also
     * passes the final reconciliation.
     */
    static List<String> unreconciledAdoptions(Connection con) throws SQLException {
        List<String> unfinished = new ArrayList<>();
        List<String> archives = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT table_schema
                FROM information_schema.columns
                WHERE table_name = 'import_run' AND column_name = 'reconciled_at'
                      AND table_schema <> current_schema()
                ORDER BY table_schema
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) archives.add(rs.getString(1));
        }
        for (String archive : archives) {
            try (PreparedStatement ps = con.prepareStatement("""
                    SELECT import_id, preserved_at
                    FROM %s.import_run
                    WHERE source_schema = current_schema() AND reconciled_at IS NULL
                    ORDER BY preserved_at
                    """.formatted(quote(archive)));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    unfinished.add("import " + rs.getString(1) + " preserved at " + rs.getString(2)
                            + " in archive schema " + archive + " was never reconciled");
                }
            }
        }
        return unfinished;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static int countRelations(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                """);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String successDiagnostics(State state, String targetVersion, List<String> applied) {
        String where = state == State.EMPTY ? "empty database" : "Flyway-managed database";
        return "Schema gate: " + where + " validated at version " + targetVersion
                + (applied.isEmpty() ? "; nothing pending." : "; applied " + applied + ".");
    }

    private static String legacyDiagnostics(String url, String schema) {
        return """
               Schema gate: this database holds the pre-migration manual schema and is NOT under \
               Flyway management. Nothing was migrated, baselined or deleted.

               Legacy account paths keep running against the legacy schema. Adopting it is an \
               explicit operation that needs the old writers stopped and a restorable backup:

                 java -cp target/classes:<deps> infra.schema.LegacySchemaAdoptionCli \
               --backup=<restore reference> --writers-stopped=<who confirmed> --confirm

               Database %s%s. See docs/CURRENT_ARCHITECTURE.md section 8.1."""
                .formatted(url, schema == null ? "" : ", schema " + schema);
    }

    private static String unfinishedAdoptionDiagnostics(String url, String schema, State state,
                                                        List<String> unfinished) {
        return """
               Schema gate: refusing to serve. This database (%s%s, state %s) holds a preserved \
               legacy archive whose adoption never completed its provenance association and \
               integrity reconciliation:

                 %s

               Nothing was deleted and the archive, manifest and ledger are intact. Serving an \
               unreconciled cutover is not allowed, because the versioned chain can already be \
               applied while the preserved dataset is still unaccounted for. Resume the adoption \
               with the old writers stopped - it continues from the committed preservation and does \
               not import twice:

                 java -cp target/classes:<deps> infra.schema.LegacySchemaAdoptionCli \
               --backup=<restore reference> --writers-stopped=<who confirmed> --confirm

               If it keeps failing, read its diagnostics, or restore the backup with a compatible \
               application version. See docs/CURRENT_ARCHITECTURE.md section 8.1."""
                .formatted(url, schema == null ? "" : ", schema " + schema, state,
                        String.join("\n  ", unfinished));
    }

    private static String migrationFailureDiagnostics(String url, String schema, State state, FlywayException e) {
        return """
               Schema gate: refusing to serve. The versioned schema at %s%s (state %s) could not be \
               validated and migrated.

               Nothing was repaired automatically: a checksum mismatch means an applied migration \
               file was edited, which is never corrected in place - add a new version instead, or \
               restore the backup with a compatible application version. A failed migration rolled \
               back its own version; earlier applied versions stay recorded and a retry resumes from \
               that history.

               Flyway reported: %s"""
                .formatted(url, schema == null ? "" : ", schema " + schema, state, e.getMessage());
    }
}
