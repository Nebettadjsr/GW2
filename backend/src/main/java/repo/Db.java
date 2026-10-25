package repo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared JDBC connection helper for both the {@code repo} and {@code sync} packages
 * (STORY-INFRA-003; previously duplicated as {@code sync.Db}).
 */
public final class Db {
    private Db() {}

    /**
     * Optional system property (never a normal environment/.env value) letting an in-process
     * UI test point every {@code Db.open()} call - including the ones made deep inside view
     * controllers that have no injectable {@link Connection} - at a disposable schema instead of
     * the developer's normal database (STORY-UI-001, docs/TEST_STRATEGY.md).
     * Unset in normal/production use, so behavior is unchanged: {@link #open()} still returns a
     * connection on the default schema exactly as before this property existed.
     */
    public static final String TEST_SCHEMA_PROPERTY = "gw2tool.test.schema";

    public static Connection open() throws SQLException {
        Connection con = DriverManager.getConnection(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS);

        String testSchema = System.getProperty(TEST_SCHEMA_PROPERTY);
        if (testSchema != null && !testSchema.isBlank()) {
            con.setSchema(testSchema);
        }

        return con;
    }
}
