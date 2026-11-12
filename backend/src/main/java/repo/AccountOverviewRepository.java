package repo;

import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Own-account saved-data counts for System Status. Reads only the published {@code CHARACTERS},
 * {@code ACCOUNT_RECIPES} and {@code MATERIALS} rows of one account, in a single read-only
 * {@code REPEATABLE READ} transaction, so a marker is never combined with rows of another publication.
 * A source without a marker is absent from the result (not loaded), whatever stray rows exist.
 */
public final class AccountOverviewRepository {

    /** {@code total} is only meaningful for materials (sum of positive quantities); otherwise null. */
    public record Counts(AccountSourceStateRepository.SourceState state, long count, Long total) {}

    public Map<String, Counts> read(Connection con, AccountScope scope, Set<String> sources) throws SQLException {
        Map<String, Counts> result = new LinkedHashMap<>();
        if (sources.isEmpty()) return result;
        ManagedSchema.requireManagedAccountSchema(con, "account overview");
        boolean autoCommit = con.getAutoCommit();
        int isolation = con.getTransactionIsolation();
        boolean readOnly = con.isReadOnly();
        try {
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            con.setReadOnly(true);
            Map<String, AccountSourceStateRepository.SourceState> states =
                    new AccountSourceStateRepository().readAll(con, scope, sources);
            for (var entry : states.entrySet()) {
                switch (entry.getKey()) {
                    case AccountSyncStateRepository.CHARACTERS -> result.put(entry.getKey(), new Counts(
                            entry.getValue(), single(con, "SELECT count(*), NULL::bigint FROM characters WHERE account_id = ?", scope), null));
                    case AccountSyncStateRepository.RECIPES -> result.put(entry.getKey(), new Counts(
                            entry.getValue(), single(con, "SELECT count(DISTINCT recipe_id), NULL::bigint FROM account_recipes WHERE account_id = ?", scope), null));
                    case AccountSyncStateRepository.MATERIALS -> result.put(entry.getKey(), materials(con, scope, entry.getValue()));
                    default -> { /* not an overview source */ }
                }
            }
            con.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try { con.rollback(); } catch (SQLException ignored) { /* original failure wins */ }
            throw e;
        } finally {
            try {
                con.setReadOnly(readOnly);
                con.setTransactionIsolation(isolation);
                con.setAutoCommit(autoCommit);
            } catch (SQLException ignored) { /* connection is discarded by the caller */ }
        }
    }

    private static long single(Connection con, String sql, AccountScope scope) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Counts materials(Connection con, AccountScope scope,
                                    AccountSourceStateRepository.SourceState state) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(DISTINCT item_id), COALESCE(sum(count::bigint), 0)::bigint
                FROM account_materials WHERE account_id = ? AND count > 0
                """)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Counts(state, rs.getLong(1), rs.getLong(2));
            }
        }
    }
}
