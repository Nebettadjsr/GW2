package application;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import sync.AccountRefreshGateway;
import java.sql.SQLException;

/** Binds the local backend's configured GW2 API key to its account-scoped Luck row. */
public class CurrentAccountLuckService {
    private final AccountLuckService luckService;
    private final AccountIdentityGateway identityGateway;
    private final AccountRefreshGateway syncGateway;

    public CurrentAccountLuckService() {
        this(new AccountLuckService(), new AccountIdentityGateway(), new AccountRefreshGateway());
    }

    public CurrentAccountLuckService(AccountLuckService luckService, AccountIdentityGateway identityGateway) {
        this(luckService, identityGateway, new AccountRefreshGateway());
    }

    public CurrentAccountLuckService(AccountLuckService luckService, AccountIdentityGateway identityGateway,
                                     AccountRefreshGateway syncGateway) {
        this.luckService = luckService;
        this.identityGateway = identityGateway;
        this.syncGateway = syncGateway;
    }

    public synchronized AccountLuckService.AccountLuckStatus currentStatus()
            throws SQLException, AccountIdentityUnavailableException, AccountLuckSyncUnavailableException {
        String accountId = identityGateway.currentAccountId();
        var stored = luckService.getStatus(accountId);
        if (stored.isPresent()) return stored.get();
        try {
            syncGateway.syncAccountLuck();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AccountLuckSyncUnavailableException(e);
        }
        return luckService.getStatus(accountId).orElseThrow(AccountLuckNotSyncedException::new);
    }

    public static class AccountIdentityGateway {
        public String currentAccountId() throws AccountIdentityUnavailableException {
            try {
                JsonNode account = Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account");
                if (account == null || !account.isObject() || !account.path("id").isTextual()
                        || account.path("id").asText().isBlank()) {
                    throw new IllegalStateException("GW2 account response has no ID");
                }
                return account.path("id").asText();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new AccountIdentityUnavailableException(e);
            }
        }
    }

    public static class AccountIdentityUnavailableException extends Exception {
        public AccountIdentityUnavailableException(Throwable cause) { super(cause); }
    }

    public static class AccountLuckSyncUnavailableException extends Exception {
        public AccountLuckSyncUnavailableException(Throwable cause) { super(cause); }
    }

    public static class AccountLuckNotSyncedException extends RuntimeException {}
}
