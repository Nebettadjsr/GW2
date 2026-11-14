package repo;

import application.ops.DatabaseSizeService;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** The one narrow size query; it reads no account rows and makes no GW2 call. */
public final class DatabaseSizeRepository implements DatabaseSizeService.Measurement {
    static final String SQL = "SELECT pg_database_size(current_database())";

    public interface Opener { Connection open() throws SQLException; }

    private final Opener opener;

    public DatabaseSizeRepository(Opener opener) { this.opener = opener; }

    @Override
    public long totalBytes() throws SQLException {
        try (Connection c = opener.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SQL)) {
            if (!rs.next()) throw new SQLException("no database size row");
            return rs.getLong(1);
        }
    }
}
