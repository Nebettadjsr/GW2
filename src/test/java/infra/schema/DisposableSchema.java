package infra.schema;

import repo.EnvConfig;
import repo.RuntimeAccessRole;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An isolated, disposable PostgreSQL schema for the migration checks (docs/TEST_STRATEGY.md §31.2).
 *
 * <p>Every check that uses it runs the production migration chain and the production adoption path
 * against a freshly created, randomly named schema and drops it afterwards, so no developer's own
 * data, tables or {@code public} schema are read or written at any point. Nothing here recreates
 * production SQL: the chain comes from {@code classpath:db/migration} and the legacy shapes from the
 * checked-in fixtures under {@code db/legacy-fixtures}.
 */
final class DisposableSchema implements AutoCloseable {

    private static final String FIXTURES = "/db/legacy-fixtures/";

    private final String url = EnvConfig.require("DATABASE_URL");
    private final String user = EnvConfig.require("DATABASE_USER");
    private final String password = EnvConfig.require("DATABASE_PASSWORD");

    private final String name;
    private final String archiveName;
    private final Connection connection;

    DisposableSchema(String prefix) throws SQLException {
        this.name = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        this.archiveName = name + "_archive";
        this.connection = DriverManager.getConnection(url, user, password);
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + name);
        }
        connection.setSchema(name);
        try (Statement st = connection.createStatement()) {
            // Deterministic rendering of instants, matching what the adoption path sets for itself.
            st.execute("SET TIME ZONE 'UTC'");
        }
    }

    String url() {
        return url;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    String name() {
        return name;
    }

    String archiveName() {
        return archiveName;
    }

    Connection connection() {
        return connection;
    }

    /** A second connection on the same schema, for checks that need an independent session. */
    Connection openAnotherConnection() throws SQLException {
        Connection other = DriverManager.getConnection(url, user, password);
        other.setSchema(name);
        try (Statement st = other.createStatement()) {
            st.execute("SET TIME ZONE 'UTC'");
        }
        return other;
    }

    /** Applies one checked-in fixture script. */
    void apply(String fixture) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(read(FIXTURES + fixture));
        }
    }

    /** Applies the required legacy inventory plus the named optional variants and their content. */
    void applyLegacy(String... fixtures) throws SQLException {
        apply("legacy_required.sql");
        for (String fixture : fixtures) apply(fixture);
        apply("legacy_seed.sql");
    }

    void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    long count(String qualifiedTable) throws SQLException {
        return scalarLong("SELECT count(*) FROM " + qualifiedTable);
    }

    long scalarLong(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    String scalarText(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    boolean scalarBoolean(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    List<String> rows(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    if (column > 1) row.append('|');
                    row.append(rs.getString(column));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    /**
     * The live schema's structure as one comparable value: every relation with its columns and its
     * complete constraint set. This is what "an empty and an adopted database converge" is asserted
     * on, so it deliberately ignores nothing except the physical ordering of constraints.
     */
    Map<String, String> liveSchemaShape() throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        for (String relation : rows("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                ORDER BY c.relname
                """)) {
            shape.put(relation, "");
        }
        for (String relation : shape.keySet().toArray(String[]::new)) {
            List<String> columns = rows("""
                    SELECT column_name || ':' || udt_name || ':' || is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = '%s'
                    ORDER BY ordinal_position
                    """.formatted(relation));
            List<String> constraints = rows("""
                    SELECT pg_get_constraintdef(con.oid)
                    FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
                    WHERE con.connamespace = current_schema()::regnamespace AND c.relname = '%s'
                    ORDER BY 1
                    """.formatted(relation));
            shape.put(relation, "columns=" + columns + " constraints=" + constraints);
        }
        return shape;
    }

    static String read(String resource) {
        try (InputStream stream = DisposableSchema.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing test resource " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
            st.execute("DROP SCHEMA IF EXISTS " + archiveName + " CASCADE");
            // An adoption of this schema also created the restricted runtime role named after it
            // (repo.RuntimeAccessRole). Roles are cluster-wide, not schema-scoped, so dropping the
            // schemas does not remove it and a run would otherwise leave one role per check behind.
            st.execute("""
                    DO $$
                    DECLARE restricted text := '%s';
                    BEGIN
                        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = restricted) THEN
                            EXECUTE format('DROP OWNED BY %%I', restricted);
                            EXECUTE format('DROP ROLE %%I', restricted);
                        END IF;
                    END $$
                    """.formatted(RuntimeAccessRole.roleNameFor(name)));
        } finally {
            connection.close();
        }
    }
}
