package repo;

import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The per-account, per-source completion markers in {@code account_source_state} (STORY-APP-015,
 * TARGET_ARCHITECTURE.md §7.2 "Use one durable source-state relation keyed by
 * {@code (account_id, source)}").
 *
 * <p>Replaces the singleton authorities it supersedes on a migrated database: the unscoped
 * {@code account_sync_state} row per source ({@link AccountSyncStateRepository}) and the
 * {@code account_materials_sync} id-1 row. Both of those answered for whichever account happened to
 * have synchronized last, which is exactly what scoping removes.
 *
 * <p>Two rules the marker carries:
 *
 * <ul>
 *   <li><b>No row means missing.</b> Not "empty", not "stale", and never derived from whether the
 *       data tables happen to hold rows - a successful empty upstream response is a real answer and
 *       gets a marker, while an empty table with no marker has never synchronized. After the cache
 *       reset there are no markers at all, and they stay absent until a fresh publication.</li>
 *   <li><b>The generation advances only on publication.</b> It starts at 1 and increases by one per
 *       successful publication of that source, including an unchanged or empty one, so a reader can
 *       tell two publications apart even when their payload and their {@code completed_at} would
 *       look alike. A timestamp is not a generation identifier.</li>
 * </ul>
 *
 * <p>{@link #read} and {@link #readAll} are pure {@code SELECT}s with no bootstrap and no write, so
 * a calculation may call them inside its {@code REPEATABLE READ} snapshot and an ordinary read never
 * marks anything fresh. Advancing a generation is {@link #advance}, and only
 * {@link AccountSourcePublication} calls it - inside the source's own transaction.
 */
public final class AccountSourceStateRepository {

    /**
     * One source's last successful publication.
     *
     * @param generation  account/source-local, strictly increasing, always {@code >= 1}
     * @param completedAt when that publication succeeded
     */
    public record SourceState(String source, long generation, Instant completedAt) {}

    /** The last successful publication of one source, or empty when that source is missing. */
    public Optional<SourceState> read(Connection con, AccountScope scope, String source) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "account_source_state lookup");
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT source, generation, completed_at
                FROM account_source_state
                WHERE account_id = ? AND source = ?
                """)) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, source);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rowOf(rs));
            }
        }
    }

    /**
     * Every requested source that has published successfully, in one statement so a capture reads
     * them all from the same point of its snapshot. A requested source with no marker is simply
     * absent from the result.
     */
    public Map<String, SourceState> readAll(Connection con, AccountScope scope, Set<String> sources)
            throws SQLException {
        Map<String, SourceState> states = new LinkedHashMap<>();
        if (sources.isEmpty()) return states;
        ManagedSchema.requireManagedAccountSchema(con, "account_source_state lookup");
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT source, generation, completed_at
                FROM account_source_state
                WHERE account_id = ? AND source = ANY(?)
                ORDER BY source
                """)) {
            ps.setObject(1, scope.accountId());
            ps.setArray(2, con.createArrayOf("text", sources.toArray(String[]::new)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SourceState state = rowOf(rs);
                    states.put(state.source(), state);
                }
            }
        }
        return states;
    }

    /**
     * Advances this account's generation for {@code source} and records {@code completedAt}.
     *
     * <p>One statement, so the read-modify-write of the generation happens under the row's own lock
     * rather than in application code: a first publication inserts generation 1, every later one
     * takes the stored value and adds one. Must run inside the publication transaction - on its own
     * it would claim a completion for data that was never committed.
     *
     * @return the generation this publication established
     */
    long advance(Connection con, AccountScope scope, String source, Instant completedAt) throws SQLException {
        if (completedAt == null) throw new IllegalArgumentException("completedAt is required");
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_source_state (account_id, source, generation, completed_at)
                VALUES (?, ?, 1, ?)
                ON CONFLICT (account_id, source) DO UPDATE SET
                    generation   = account_source_state.generation + 1,
                    completed_at = EXCLUDED.completed_at
                RETURNING generation
                """)) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, source);
            ps.setTimestamp(3, Timestamp.from(completedAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Advancing " + source + " returned no generation");
                return rs.getLong(1);
            }
        }
    }

    private static SourceState rowOf(ResultSet rs) throws SQLException {
        return new SourceState(rs.getString("source"), rs.getLong("generation"),
                rs.getTimestamp("completed_at").toInstant());
    }
}
