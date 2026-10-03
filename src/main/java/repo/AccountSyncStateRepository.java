package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Durable completion timestamps for account data sources. A timestamp is written with the source's data transaction. */
public final class AccountSyncStateRepository {
    public static final String BANK = "BANK";
    public static final String MATERIALS = "MATERIALS";
    public static final String RECIPES = "ACCOUNT_RECIPES";
    public static final String LUCK = "LUCK";
    public static final String CHARACTERS = "CHARACTERS";

    public static void ensure(Connection con) throws SQLException {
        try (var statement = con.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS account_sync_state (source text PRIMARY KEY, fetched_at timestamptz NOT NULL)");
        }
    }

    public static void mark(Connection con, String source, Instant fetchedAt) throws SQLException {
        ensure(con);
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_sync_state(source, fetched_at) VALUES (?, ?)
                ON CONFLICT (source) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
                """)) {
            ps.setString(1, source);
            ps.setTimestamp(2, Timestamp.from(fetchedAt));
            ps.executeUpdate();
        }
    }

    public Optional<Instant> find(Connection con, String source) throws SQLException {
        ensure(con);
        try (PreparedStatement ps = con.prepareStatement("SELECT fetched_at FROM account_sync_state WHERE source = ?")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty();
            }
        }
    }
}
