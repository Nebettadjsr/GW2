package repo;

import model.AccountLuck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/** Persistence boundary for account-scoped consumed Luck. */
public class AccountLuckRepository {
    public void save(Connection con, AccountLuck luck) throws SQLException {
        // The migrated account_luck is keyed by the registry's verified GUID, so a row may only be
        // written for a registered account (STORY-INFRA-004). This path carries the upstream id
        // without that registration, and the registry write belongs to the scoped account stories.
        ManagedSchema.rejectUnscopedAccountAccess(con, "account_luck without a registered account");
        String sql = """
                INSERT INTO account_luck (account_id, consumed_luck, fetched_at)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET
                    consumed_luck = EXCLUDED.consumed_luck,
                    fetched_at = EXCLUDED.fetched_at
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, luck.accountId());
            ps.setLong(2, luck.consumedLuck());
            ps.setTimestamp(3, Timestamp.from(luck.fetchedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<AccountLuck> find(String accountId) throws SQLException {
        try (Connection con = Db.open()) {
            return find(con, accountId);
        }
    }

    public Optional<AccountLuck> find(Connection con, String accountId) throws SQLException {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId is required");
        ManagedSchema.rejectUnscopedAccountAccess(con, "account_luck without a registered account");
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT account_id, consumed_luck, fetched_at FROM account_luck WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new AccountLuck(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant()));
            }
        }
    }
}
