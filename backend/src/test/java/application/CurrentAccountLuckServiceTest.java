package application;

import luck.MagicFindProgression;
import model.AccountLuck;
import org.junit.jupiter.api.Test;
import repo.AccountLuckRepository;
import sync.AccountRefreshGateway;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentAccountLuckServiceTest {
    @Test
    void synchronizesOnlyMissingLuckThenReadsTheConfiguredAccount() throws Exception {
        var stored = new AtomicReference<AccountLuck>();
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(String accountId) {
                assertEquals("configured-account", accountId);
                return Optional.ofNullable(stored.get());
            }
        };
        var identity = new CurrentAccountLuckService.AccountIdentityGateway() {
            @Override public String currentAccountId() { return "configured-account"; }
        };
        var calls = new int[1];
        var sync = new AccountRefreshGateway() {
            @Override public void syncAccountLuck() {
                calls[0]++;
                stored.set(new AccountLuck("configured-account", 14_500, Instant.EPOCH));
            }
        };
        var current = new CurrentAccountLuckService(
                new AccountLuckService(repository, MagicFindProgression.canonical()), identity, sync);
        assertEquals(14_500, current.currentStatus().progress().consumedLuck());
        assertEquals(14_500, current.currentStatus().progress().consumedLuck());
        assertEquals(1, calls[0]);
    }

    @Test
    void reportsFailedColdLuckSyncExplicitly() {
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(String accountId) { return Optional.empty(); }
        };
        var identity = new CurrentAccountLuckService.AccountIdentityGateway() {
            @Override public String currentAccountId() { return "configured-account"; }
        };
        var sync = new AccountRefreshGateway() {
            @Override public void syncAccountLuck() throws Exception { throw new java.io.IOException("GW2 unavailable"); }
        };
        var current = new CurrentAccountLuckService(
                new AccountLuckService(repository, MagicFindProgression.canonical()), identity, sync);
        assertThrows(CurrentAccountLuckService.AccountLuckSyncUnavailableException.class, current::currentStatus);
    }

    @Test
    void readsOnlyTheAccountResolvedFromBackendIdentity() throws Exception {
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(String accountId) {
                assertEquals("configured-account", accountId);
                return Optional.of(new AccountLuck(accountId, 14_500, Instant.EPOCH));
            }
        };
        var identity = new CurrentAccountLuckService.AccountIdentityGateway() {
            @Override public String currentAccountId() { return "configured-account"; }
        };
        var current = new CurrentAccountLuckService(
                new AccountLuckService(repository, MagicFindProgression.canonical()), identity);
        assertEquals(14_500, current.currentStatus().progress().consumedLuck());
    }
}
