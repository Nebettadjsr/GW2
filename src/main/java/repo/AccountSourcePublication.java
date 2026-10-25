package repo;

import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * The transaction that publishes one account source (STORY-APP-015, TARGET_ARCHITECTURE.md §7.2
 * "Fetch and validate a source outside the database transaction, then publish all of its
 * replacements/upserts/stale-row removals, generation/completion marker and account sync activity
 * in one transaction").
 *
 * <p>The protocol lives here rather than being repeated per source so every source gets the same
 * five guarantees:
 *
 * <ol>
 *   <li><b>Nothing upstream happens inside it.</b> {@link #publish} receives an already-fetched,
 *       already-validated payload; no transaction ever spans a GW2 API call.</li>
 *   <li><b>Writers serialize per account.</b> The transaction takes a per-account advisory lock
 *       before writing, so two publications for one account queue instead of interleaving their
 *       upserts and stale-row deletions - and two publications for <em>different</em> accounts take
 *       different keys, so neither waits for the other (§9.1 "Serialize writers per account").</li>
 *   <li><b>Data, stale-row removal, marker and activity commit together.</b> A reader therefore
 *       cannot see refreshed rows under the previous marker, or a new marker over half-replaced
 *       rows.</li>
 *   <li><b>Failure changes nothing.</b> Any exception rolls the whole transaction back, so the
 *       last-good rows, generation and completion time stay exactly as they were. Sources published
 *       in earlier transactions are already committed and unaffected, which is what lets independent
 *       sources succeed while another fails.</li>
 *   <li><b>Success always advances the generation.</b> Including a successful empty or unchanged
 *       payload: the publication happened, so the marker is valid and the generation moves.</li>
 * </ol>
 *
 * <p>The caller owns the connection and must not have started a transaction on it; this method sets
 * {@code autoCommit(false)} and ends the transaction itself.
 */
public final class AccountSourcePublication {

    private final AccountRegistryRepository registry = new AccountRegistryRepository();
    private final AccountSourceStateRepository sourceState = new AccountSourceStateRepository();

    /**
     * One source's already-fetched, already-validated rows, written inside the publication
     * transaction.
     *
     * <p>{@code runTs} is the transaction's own {@code now()}, taken once and passed to every
     * statement, so "the rows this run refreshed" and "the rows it did not" are decidable by a
     * single value: an upsert stamps {@code fetched_at = runTs} and the matching stale-row delete
     * removes this account's rows with an older stamp. Taking it from the database rather than the
     * JVM keeps the comparison in the same clock as the stored column.
     */
    public interface Payload {
        void write(Connection con, AccountScope scope, Timestamp runTs) throws Exception;
    }

    /** What a successful publication established. */
    public record Published(String source, long generation, Instant completedAt) {}

    /**
     * Publishes {@code source} for {@code scope}.
     *
     * @param completedAt the instant to record as this source's successful completion
     * @throws Exception whatever the payload threw, after the transaction was rolled back
     */
    public Published publish(Connection con,
                             AccountScope scope,
                             String source,
                             Instant completedAt,
                             Payload payload) throws Exception {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (completedAt == null) throw new IllegalArgumentException("completedAt is required");
        ManagedSchema.requireManagedAccountSchema(con, "publication of account source " + source);

        con.setAutoCommit(false);
        try {
            lockAccount(con, scope);
            registry.register(con, scope);

            Timestamp runTs = transactionNow(con);
            payload.write(con, scope, runTs);

            long generation = sourceState.advance(con, scope, source, completedAt);
            registry.recordSuccessfulSync(con, scope, completedAt);

            con.commit();
            return new Published(source, generation, completedAt);
        } catch (Exception failure) {
            try {
                con.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /**
     * Takes this account's writer lock for the rest of the transaction.
     *
     * <p>{@code pg_advisory_xact_lock} rather than a session lock, so commit and rollback both
     * release it and no path can leak it. It is not a lock on the registry row, because the lock
     * must also cover the first publication - the one that inserts that row - and because a row lock
     * would make every ordinary authorized read (which updates {@code last_seen_at}) wait for a
     * running publication. The key is a 64-bit digest of the whole GUID; see {@link #writerLockKey}.
     */
    private static void lockAccount(Connection con, AccountScope scope) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, writerLockKey(scope));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    /**
     * The first 64 bits of SHA-256 over all 128 bits of the GUID. A fold such as {@code msb ^ lsb}
     * collides for trivially constructed distinct GUIDs; a cryptographic digest does not, and a
     * residual 64-bit collision could only make two accounts queue behind each other - it can never
     * let a writer through or mix their data.
     */
    static long writerLockKey(AccountScope scope) {
        try {
            java.nio.ByteBuffer id = java.nio.ByteBuffer.allocate(16)
                    .putLong(scope.accountId().getMostSignificantBits())
                    .putLong(scope.accountId().getLeastSignificantBits());
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.array());
            return java.nio.ByteBuffer.wrap(digest).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    private static Timestamp transactionNow(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT now()");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("SELECT now() returned no row");
            return rs.getTimestamp(1);
        }
    }
}
