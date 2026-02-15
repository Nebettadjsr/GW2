import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DbBootstrap {
    private DbBootstrap() {}

    /**
     * Ensures the database in jdbc:postgresql://host:port/DBNAME exists.
     * If not, connects to the maintenance DB ("postgres") and creates it.
     *
     * Requires that the user in AppConfig.DB_USER has CREATEDB privilege (or is superuser).
     */
    public static void ensureDatabaseExists(String targetDbUrl, String user, String pass) throws SQLException {
        DbUrlParts p = parsePostgresJdbcUrl(targetDbUrl);

        // Connect to maintenance DB (postgres) on same host/port
        String adminUrl = "jdbc:postgresql://" + p.host + ":" + p.port + "/postgres";

        try (Connection con = DriverManager.getConnection(adminUrl, user, pass);
             Statement st = con.createStatement()) {

            // 1) check existence
            String checkSql = "SELECT 1 FROM pg_database WHERE datname = '" + escapeSqlLiteral(p.dbName) + "'";
            try (ResultSet rs = st.executeQuery(checkSql)) {
                if (rs.next()) {
                    return; // already exists
                }
            }

            // 2) create DB (cannot parameterize identifiers -> we quote safely)
            String createSql = "CREATE DATABASE " + quoteIdentifier(p.dbName);
            st.executeUpdate(createSql);
        }
    }

    // ---------- helpers ----------

    private static String quoteIdentifier(String ident) {
        // Postgres identifier quoting: double quotes, and double any embedded quotes
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String escapeSqlLiteral(String s) {
        // for SQL string literals: single quotes doubled
        return s.replace("'", "''");
    }

    private static class DbUrlParts {
        final String host;
        final int port;
        final String dbName;
        DbUrlParts(String host, int port, String dbName) {
            this.host = host;
            this.port = port;
            this.dbName = dbName;
        }
    }

    private static DbUrlParts parsePostgresJdbcUrl(String url) {
        // expects: jdbc:postgresql://host:port/dbname  (port optional)
        Pattern pat = Pattern.compile("^jdbc:postgresql://([^/:]+)(?::(\\d+))?/([^?]+).*$");
        Matcher m = pat.matcher(url);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unsupported DB_URL format: " + url);
        }
        String host = m.group(1);
        int port = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 5432;
        String db = m.group(3);
        return new DbUrlParts(host, port, db);
    }
}