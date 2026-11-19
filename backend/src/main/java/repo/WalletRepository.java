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

    /**
     * As {@link #load(Connection, AccountScope)}. Age is not judged here: freshness is the configured account
     * freshness policy's decision (it marks the WALLET source STALE in the calculation capture and admits a
     * refresh), exactly as for the bank and materials. The last successful publication stays the data until then.
     */
    public Optional<Map<Integer, Integer>> load(Connection con, AccountScope scope, java.time.Instant ignoredNow)
            throws SQLException {
        if (con == null) return Optional.empty();
        Optional<AccountSourceStateRepository.SourceState> marker =
                new AccountSourceStateRepository().read(con, scope, AccountSyncStateRepository.WALLET);
        if (marker.isEmpty()) {
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
