package repo;

import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The account registry and its activity timestamps (STORY-APP-015, TARGET_ARCHITECTURE.md §7.1-7.2).
 *
 * <p>Registration is an {@code ON CONFLICT DO NOTHING} insert of the verified GUID, so it is
 * idempotent for concurrent callers by the primary key itself rather than by a check-then-insert
 * race: two requests proving the same account both succeed and both end up pointing at the one
 * dataset. A replacement API key resolving to the same GUID therefore reuses it - the registry has
 * no key, key hash, session or application-user column to distinguish them by, which is also why
 * nothing here can store a credential.
 *
 * <p>Both activity updates are monotonic through {@code GREATEST}, which in PostgreSQL ignores NULL
 * arguments - so a NULL (unknown) timestamp is filled by the first real event, and a late-arriving
 * earlier instant from a concurrent request cannot move either column backwards. Missing historical
 * activity stays NULL; nothing in this class fabricates a visit or a synchronization.
 *
 * <p>They differ in whether they may establish the identity they describe.
 * {@link #recordAuthorizedUse} registers the verified account if it is not registered yet, because a
 * successful authorized use is the account's first observable event and its activity would otherwise
 * be silently discarded - it is the one event that can happen before any publication.
 * {@link #recordSuccessfulSync} stays a plain {@code UPDATE}: it is written inside
 * {@link AccountSourcePublication}, which has already registered the account in the same
 * transaction, so an upsert there could only mask a missing registration.
 *
 * <p>Activity is never freshness. {@code last_successful_sync_at} means only that <em>some</em>
 * source published successfully; the freshness authority is {@code account_source_state}, read
 * through {@link AccountSourceStateRepository}.
 */
public final class AccountRegistryRepository {

    /** One registry row. A null instant means unknown, never "never" and never "now". */
    public record Activity(String accountId,
                           String displayName,
                           Instant createdAt,
                           Instant lastSeenAt,
                           Instant lastSuccessfulSyncAt) {}

    /**
     * Creates the account's registry row if it does not exist yet.
     *
     * @return true when this call created the row, false when it already existed
     */
    public boolean register(Connection con, AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "account_registry registration");
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_registry (account_id) VALUES (?)
                ON CONFLICT (account_id) DO NOTHING
                """)) {
            ps.setObject(1, scope.accountId());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Registers or reuses the verified account and records a successfully authorized use at
     * {@code seenAt}, monotonically.
     *
     * <p>Called by an ordinary authorized read, which is why it must not advance any freshness
     * marker: it touches {@code last_seen_at} alone (§7.1 "ordinary authorized reads may advance
     * activity but never freshness"). Rejected scope and failed proof never reach it, so neither
     * advances activity.
     *
     * <p>One statement, so registration and the activity it describes are atomic: an account whose
     * first event is an authorized read - any account that has not published a source yet, including
     * every account after a cache reset - gets its registry row and its {@code last_seen_at} together
     * or not at all. {@code DO UPDATE ... GREATEST} keeps the already-registered case monotonic and
     * leaves {@code display_name}, {@code created_at} and {@code last_successful_sync_at} untouched,
     * so a visit still fabricates no synchronization and rewrites no history.
     *
     * @return true when the use was recorded
     */
    public boolean recordAuthorizedUse(Connection con, AccountScope scope, Instant seenAt) throws SQLException {
        if (seenAt == null) throw new IllegalArgumentException("activity instant is required");
        ManagedSchema.requireManagedAccountSchema(con, "account activity (last_seen_at)");
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_registry (account_id, last_seen_at) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET
                  last_seen_at = GREATEST(account_registry.last_seen_at, EXCLUDED.last_seen_at)
                """)) {
            ps.setObject(1, scope.accountId());
            ps.setTimestamp(2, Timestamp.from(seenAt));
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Records that some source published successfully at {@code syncedAt}, monotonically. Written
     * inside that source's publication transaction, so a rolled-back publication leaves it alone.
     *
     * @return true when a registry row was updated, false when the account is not registered
     */
    public boolean recordSuccessfulSync(Connection con, AccountScope scope, Instant syncedAt) throws SQLException {
        return monotonicUpdate(con, scope, "last_successful_sync_at", syncedAt,
                "account activity (last_successful_sync_at)");
    }

    public Optional<Activity> find(Connection con, AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "account_registry lookup");
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT account_id, display_name, created_at, last_seen_at, last_successful_sync_at
                FROM account_registry
                WHERE account_id = ?
                """)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Activity(
                        rs.getString("account_id"),
                        rs.getString("display_name"),
                        instantOrNull(rs, "created_at"),
                        instantOrNull(rs, "last_seen_at"),
                        instantOrNull(rs, "last_successful_sync_at")));
            }
        }
    }

    private boolean monotonicUpdate(Connection con, AccountScope scope, String column, Instant at,
                                    String what) throws SQLException {
        if (at == null) throw new IllegalArgumentException("activity instant is required");
        ManagedSchema.requireManagedAccountSchema(con, what);
        // The column name is one of this class's own literals, never caller input.
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE account_registry SET " + column + " = GREATEST(" + column + ", ?) WHERE account_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setObject(2, scope.accountId());
            return ps.executeUpdate() == 1;
        }
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
