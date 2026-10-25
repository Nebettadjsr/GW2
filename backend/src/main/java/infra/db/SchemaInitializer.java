package infra.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.postgresql.ds.PGSimpleDataSource;
import repo.AppConfig;
import repo.Db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The startup schema gate (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.3-7.4).
 *
 * <p>Every retained entry point runs this before any repository, scheduler or background task can
 * touch the database, and the process does not continue when it fails - so service access resumes
 * only once a valid managed schema is ready.
 *
 * <p>Three states, decided from the database itself rather than from configuration:
 *
 * <ul>
 *   <li><b>managed</b> - Flyway's history table is present. The chain is validated and any pending
 *       version applied. A checksum mismatch or a failed migration fails startup; neither ever
 *       resets anything.</li>
 *   <li><b>empty</b> - the schema holds no tables. The complete versioned chain runs.</li>
 *   <li><b>unmanaged</b> - tables exist without that history: a legacy manual schema, or one this
 *       build does not recognize. Because the database is a disposable cache of GW2 API data
 *       (UD-016), the cached tables are dropped explicitly here and the complete chain is then
 *       applied to the emptied schema. Nothing is adopted, baselined, archived or inferred.</li>
 * </ul>
 *
 * <p>The gate makes no upstream call and writes no account row, so it can neither invent an account
 * nor claim a source was synchronized: after a reset every source is simply missing until a
 * validated credential drives a fresh synchronization.
 */
public final class SchemaInitializer {

    /** Flyway's history table - the marker that the versioned chain owns a schema. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    /** Where the versioned SQL lives; {@code TARGET_ARCHITECTURE.md} §7.3 fixes this location. */
    public static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * The migration policies §7.3 requires to stay off, stated where a check can read them.
     *
     * @param baselineOnMigrate automatic baselining of a non-empty schema - always {@code false},
     *                          so an existing database is never silently declared current
     * @param cleanDisabled     Flyway's destructive {@code clean} - always {@code true} (disabled);
     *                          the explicit reset below is the only way cached data is dropped
     * @param repairOnMigrate   automatic checksum repair - always {@code false}; this class never
     *                          calls {@code repair()}, so a mismatch fails closed
     */
    public record MigrationPolicy(boolean baselineOnMigrate, boolean cleanDisabled, boolean repairOnMigrate) {}

    private static final MigrationPolicy POLICY = new MigrationPolicy(false, true, false);

    /** What the gate found before it migrated, for the diagnostic it logs. */
    private enum SchemaState { EMPTY, MANAGED, UNMANAGED }

    private SchemaInitializer() {}

    /** The policies this gate applies, so a check can assert them without starting a database. */
    public static MigrationPolicy configuration() {
        return POLICY;
    }

    /**
     * Runs the gate for the configured runtime database. This is the call every retained entry
     * point makes as its first statement.
     */
    public static void initializeRuntimeDatabase() {
        initialize(runtimeDataSource());
    }

    /** Validates or creates the schema {@code dataSource} points at. */
    public static void initialize(DataSource dataSource) {
        initialize(dataSource, null);
    }

    /**
     * Same gate, with a deterministic transactional migration failure appended to the chain.
     *
     * <p>Only a check uses the second argument, to drive the rollback/retry behavior §7.3 requires
     * without shipping a broken migration: the injected version runs in the same transaction Flyway
     * gives every other version, so its failure rolls back both its own statements and its history
     * row. Production calls {@link #initialize(DataSource)}, where it is {@code null}.
     *
     * @param failureInjectionSql SQL executed as the last version of the chain, or {@code null}
     */
    public static void initialize(DataSource dataSource, String failureInjectionSql) {
        String schema;
        SchemaState state;
        try (Connection connection = dataSource.getConnection()) {
            schema = currentSchema(connection);
            state = classify(connection, schema);
            if (state == SchemaState.UNMANAGED) {
                // Section 7.4: the one place cached data is dropped, and only as this explicit gate
                // action. It runs before the Spring context exists, so no scheduler, task or legacy
                // writer is running while it does.
                List<String> dropped = resetCachedTables(connection, schema);
                System.out.println("[schema] " + schema + ": unmanaged schema reset, dropped "
                        + dropped.size() + " relation(s): " + String.join(", ", dropped));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not inspect the database schema before migrating it. The backend does not"
                            + " serve requests until a valid managed schema is ready.", e);
        }

        flyway(dataSource, schema, failureInjectionSql).migrate();
        System.out.println("[schema] " + schema + ": versioned chain applied (found " + state + ").");
    }

    private static Flyway flyway(DataSource dataSource, String schema, String failureInjectionSql) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .defaultSchema(schema)
                .schemas(schema)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(POLICY.baselineOnMigrate())
                .cleanDisabled(POLICY.cleanDisabled())
                .validateOnMigrate(true);
        if (failureInjectionSql != null) {
            configuration = configuration.javaMigrations(new InjectedFailure(failureInjectionSql));
        }
        return configuration.load();
    }

    private static String currentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_schema()")) {
            result.next();
            String schema = result.getString(1);
            if (schema == null || schema.isBlank()) {
                throw new SQLException("The connection resolves no current schema, so the gate cannot"
                        + " decide which schema the versioned chain owns. Check search_path.");
            }
            return schema;
        }
    }

    private static SchemaState classify(Connection connection, String schema) throws SQLException {
        if (relationExists(connection, schema, HISTORY_TABLE)) return SchemaState.MANAGED;
        return relations(connection, schema).isEmpty() ? SchemaState.EMPTY : SchemaState.UNMANAGED;
    }

    private static boolean relationExists(Connection connection, String schema, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                               WHERE table_schema = ? AND table_name = ?)
                """)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet result = ps.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    /** Every table and view in {@code schema}, as {@code name} / {@code TABLE|VIEW} pairs. */
    private static List<String[]> relations(Connection connection, String schema) throws SQLException {
        List<String[]> found = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT table_name, table_type FROM information_schema.tables
                WHERE table_schema = ? ORDER BY table_name
                """)) {
            ps.setString(1, schema);
            try (ResultSet result = ps.executeQuery()) {
                while (result.next()) found.add(new String[]{result.getString(1), result.getString(2)});
            }
        }
        return found;
    }

    /**
     * Drops every table, view and standalone sequence in {@code schema}.
     *
     * <p>Deliberately shape-blind: §7.4 wants a legacy schema and an unrecognized one treated the
     * same way, so no fingerprint, table whitelist or ownership inference decides what goes. The
     * drops run in one transaction, so a schema is either emptied or untouched.
     */
    private static List<String> resetCachedTables(Connection connection, String schema) throws SQLException {
        List<String> dropped = new ArrayList<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String[] relation : relations(connection, schema)) {
                String keyword = "VIEW".equals(relation[1]) ? "VIEW" : "TABLE";
                statement.execute("DROP " + keyword + " IF EXISTS " + qualify(schema, relation[0]) + " CASCADE");
                dropped.add(relation[0]);
            }
            for (String sequence : sequences(connection, schema)) {
                statement.execute("DROP SEQUENCE IF EXISTS " + qualify(schema, sequence) + " CASCADE");
                dropped.add(sequence);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return dropped;
    }

    private static List<String> sequences(Connection connection, String schema) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?")) {
            ps.setString(1, schema);
            try (ResultSet result = ps.executeQuery()) {
                while (result.next()) found.add(result.getString(1));
            }
        }
        return found;
    }

    private static String qualify(String schema, String name) {
        return quote(schema) + "." + quote(name);
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * The configured runtime database, as the only {@link DataSource} this process builds.
     *
     * <p>{@code repo.Db.TEST_SCHEMA_PROPERTY} is honored for the same reason {@code Db.open()}
     * honors it: an in-process UI check points every connection at a disposable schema, and the
     * gate has to migrate that schema rather than the developer's own.
     */
    private static DataSource runtimeDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(AppConfig.DB_URL);
        dataSource.setUser(AppConfig.DB_USER);
        dataSource.setPassword(AppConfig.DB_PASS);
        String testSchema = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        if (testSchema != null && !testSchema.isBlank()) dataSource.setCurrentSchema(testSchema);
        return dataSource;
    }

    /** The injected last version of {@link #initialize(DataSource, String)}. */
    private record InjectedFailure(String sql) implements JavaMigration {

        @Override public MigrationVersion getVersion() {
            return MigrationVersion.fromVersion("9000");
        }

        @Override public String getDescription() {
            return "injected transactional failure";
        }

        @Override public Integer getChecksum() {
            return sql.hashCode();
        }

        @Override public boolean canExecuteInTransaction() {
            return true;
        }

        @Override public void migrate(Context context) throws Exception {
            try (Statement statement = context.getConnection().createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
