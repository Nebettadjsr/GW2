package repo;

import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Account-scoped wallet balances (currency id to balance, never an item id). */
public class WalletRepository {

    /**
     * The account's published wallet, or empty when no wallet publication exists for that account (never
     * refreshed, refused for lack of the grant). Empty means unknown - not zero balances. Only this account's
     * rows are read, and the marker and rows come from the caller's one snapshot.
     */
    public Optional<Map<Integer, Integer>> load(Connection con, AccountScope scope) throws SQLException {
        return load(con, scope, java.time.Instant.now());
    }

    /** As {@link #load(Connection, AccountScope)} with no age limit (the last publication stays the data). */
    public Optional<Map<Integer, Integer>> load(Connection con, AccountScope scope, java.time.Instant ignoredNow)
            throws SQLException {
        return load(con, scope, ignoredNow, null);
    }

    /**
     * A wallet balance is only affordability evidence while fresh (DOMAIN_SPEC 2.1.0): a publication older than the
     * configured account {@code maxAge} at the injected evaluation instant is unknown, i.e. empty, exactly like
     * never published. The boundary matches the source freshness rule (stale only strictly after maxAge). A null
     * {@code maxAge} applies no limit. Refresh demand is decided separately from the same marker and age.
     */
    public Optional<Map<Integer, Integer>> load(Connection con, AccountScope scope, java.time.Instant now,
                                                java.time.Duration maxAge) throws SQLException {
        if (con == null) return Optional.empty();
        Optional<AccountSourceStateRepository.SourceState> marker =
                new AccountSourceStateRepository().read(con, scope, AccountSyncStateRepository.WALLET);
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        if (maxAge != null && now != null && marker.get().completedAt().plus(maxAge).isBefore(now)) {
            return Optional.empty();
        }
        Map<Integer, Integer> balances = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT currency_id, value FROM account_wallet WHERE account_id = ?")) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getInt(1), (int) Math.min(Integer.MAX_VALUE, rs.getLong(2)));
                }
            }
        }
        return Optional.of(balances);
    }
}
