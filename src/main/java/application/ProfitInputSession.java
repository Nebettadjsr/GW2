package application;

import repo.AccountSyncStateRepository;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One calculation's captured read view of the persisted inputs (TARGET_ARCHITECTURE.md §9.1).
 *
 * <p>Every input a single Crafting Profit calculation reads — the account source completion
 * markers, the Trading Post coverage report, recipe knowledge, the character roster, the owned
 * inventory pools, quotes and item metadata — is read through this one connection inside one
 * {@code REPEATABLE READ} transaction. PostgreSQL therefore answers all of them from a single
 * committed snapshot, which is what makes the two invariants the section states hold:
 *
 * <ul>
 *   <li>a reader cannot combine data a refresh wrote part-way through with the previous run's
 *       still-current completion marker, because a refresh publishes a source's data and its marker
 *       in one transaction and this snapshot sees either both or neither;</li>
 *   <li>a reader cannot mix input generations inside one calculation, because every statement it
 *       issues reads the same snapshot regardless of what commits meanwhile.</li>
 * </ul>
 *
 * <p>The transaction is never written to and is rolled back on {@link #close()}, so holding it open
 * for the calculation publishes nothing and blocks no refresh write.
 *
 * <p>{@link #detached()} is the seam application-layer tests use: it carries no connection, so the
 * collaborators fall back to their own connection-less reads and can be replaced by in-memory
 * fakes without a database (docs/TEST_STRATEGY.md §8).
 */
public final class ProfitInputSession implements AutoCloseable {

    /**
     * The completion-marker table is created on demand rather than at startup, because the HTTP
     * context must start without a reachable database (see {@code Gw2ApiApplicationBootTest}). It is
     * ensured once per process and outside the snapshot transaction, so no calculation issues DDL.
     */
    private static final AtomicBoolean MARKER_SCHEMA_ENSURED = new AtomicBoolean(false);

    private final Connection connection;

    private ProfitInputSession(Connection connection) {
        this.connection = connection;
    }

    public static ProfitInputSession open() throws SQLException {
        Connection con = Db.open();
        try {
            if (!MARKER_SCHEMA_ENSURED.get()) {
                AccountSyncStateRepository.ensure(con);
                MARKER_SCHEMA_ENSURED.set(true);
            }
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return new ProfitInputSession(con);
        } catch (SQLException | RuntimeException failure) {
            closeQuietly(con);
            throw failure;
        }
    }

    /** A session with no connection: collaborators read through their own, as they did before. */
    public static ProfitInputSession detached() {
        return new ProfitInputSession(null);
    }

    /** The pinned connection, or null for a {@link #detached()} session. */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // A read-only transaction has nothing to preserve; closing is what matters.
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection con) {
        try {
            con.close();
        } catch (SQLException ignored) {
            // Nothing a caller could do about it, and the calculation already answered.
        }
    }

    /** How {@link CachedCraftingProfitService} obtains a session; substitutable in tests. */
    public interface Factory {
        ProfitInputSession open() throws SQLException;
    }
}
