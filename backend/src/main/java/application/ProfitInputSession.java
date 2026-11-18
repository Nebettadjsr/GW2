package application;

import model.AccountScope;
import repo.AccountRegistryRepository;
import repo.Db;
import repo.ManagedSchema;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * One calculation's captured read view of the persisted inputs.
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

    private final Connection connection;
    private final AccountScope scope;

    /**
     * An unscoped session over an already-prepared connection. Kept with exactly this signature
     * because {@code CraftingProfitServiceSessionTest.sessionOn} reaches it by reflection to build a
     * session over a connection it supplies, without reaching a database.
     */
    private ProfitInputSession(Connection connection) {
        this(connection, null);
    }

    private ProfitInputSession(Connection connection, AccountScope scope) {
        this.connection = connection;
        this.scope = scope;
    }

    /**
     * One account's {@code REPEATABLE READ} snapshot across scoped rows, source markers and
     * shared inputs.
     *
     * <p>A single transaction with the account it belongs to carried alongside: the account's rows, the account's source markers and the shared global inputs are
     * all read from it, so the two invariants above hold per account and the snapshot metadata can
     * say which account and which generations it saw.
     *
     * <p>No marker-table bootstrap: {@code account_source_state} is Flyway-owned, so a session
     * issues no DDL at all and the transaction stays read-only from its first statement.
     *
     * <p>The authorized use is recorded <em>before</em> the transaction begins, while the connection
     * is still in autocommit, for two reasons. Activity is not part of this snapshot's consistency
     * contract, and the snapshot transaction must never become a writing one - an open writing
     * transaction would hold row locks for the whole calculation. Activity is also not freshness:
     * {@link AccountRegistryRepository#recordAuthorizedUse} registers or reuses the verified account
     * and advances {@code last_seen_at} only, so an account whose first event is a calculation does
     * get its activity recorded, while nothing here can create or advance a source marker.
     */
    public static ProfitInputSession open(AccountScope scope) throws SQLException {
        Connection con = Db.open();
        try {
            ManagedSchema.requireManagedAccountSchema(con, "account-scoped calculation input snapshot");
            new AccountRegistryRepository().recordAuthorizedUse(con, scope, Instant.now());
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return new ProfitInputSession(con, scope);
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

    /**
     * The account this snapshot belongs to, or null when it has none.
     *
     * <p>A null scope is not "the default account": production {@code open()} always binds one.
     * Null belongs only to detached or reflection-created in-memory test seams. Collaborators
     * branch on it to choose the scoped repository read, never to pick an account.
     */
    public AccountScope scope() {
        return scope;
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
        /**
         * One account's session (STORY-APP-015).
         *
         */
        ProfitInputSession open(AccountScope scope) throws SQLException;
    }
}
