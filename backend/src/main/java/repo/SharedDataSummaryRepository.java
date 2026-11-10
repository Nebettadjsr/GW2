package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight, read-only aggregate summaries over the shared tables and the account registry for the
 * owner-only System Status. Counts describe stored shared rows only; they never say anything about an
 * account's unlocks and never prove that a full-catalog refresh completed. Nothing here returns an
 * account identifier.
 */
public class SharedDataSummaryRepository {

    /** Stored-row coverage and per-record fetch range. Timestamps are over timestamped rows only. */
    public record Coverage(long storedRows, long timestampedRows, long unknownTimestampRows,
                           Instant oldestFetchedAt, Instant newestFetchedAt) {}

    /** Aggregate account counts evaluated at one instant. */
    public record AccountUsage(long totalAccounts, long activeLast60Minutes) {}

    public static final Duration ACTIVE_WINDOW = Duration.ofMinutes(60);

    public Coverage items(Connection con) throws SQLException { return coverage(con, "items"); }

    public Coverage recipes(Connection con) throws SQLException { return coverage(con, "recipes"); }

    public Coverage tradingPostPrices(Connection con) throws SQLException { return coverage(con, "tp_prices"); }

    private static Coverage coverage(Connection con, String table) throws SQLException {
        // The table name is one of three compile-time constants above, never caller input.
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*), count(fetched_at), "
                + "MIN(fetched_at), MAX(fetched_at) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            long total = rs.getLong(1);
            long stamped = rs.getLong(2);
            return new Coverage(total, stamped, total - stamped, instant(rs, 3), instant(rs, 4));
        }
    }

    /** Distinct registry rows, and those with {@code last_seen_at >= evaluatedAt - 60 minutes}. */
    public AccountUsage accounts(Connection con, Instant evaluatedAt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*), count(*) FILTER (WHERE last_seen_at >= ?) FROM account_registry
                """)) {
            ps.setTimestamp(1, Timestamp.from(evaluatedAt.minus(ACTIVE_WINDOW)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new AccountUsage(rs.getLong(1), rs.getLong(2));
            }
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
