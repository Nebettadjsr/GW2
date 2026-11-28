package application;

import luck.MagicFindProgression;
import model.AccountLuck;
import org.junit.jupiter.api.Test;
import repo.AccountLuckRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AccountLuckServiceTest {
    @Test
    void resolvesThePreviouslyDisplayedStaleValueAndCurrentApiValueAgainstCanonicalThresholds() {
        var service = new AccountLuckService(null, MagicFindProgression.canonical());
        var stale = service.resolve(new AccountLuck("acct", 1_454_790, Instant.EPOCH));
        assertEquals(204, stale.progress().magicFindPercent());
        assertEquals(18_490, stale.progress().remainingToNext());

        var current = service.resolve(new AccountLuck("acct", 1_473_850, Instant.EPOCH));
        assertEquals(205, current.progress().magicFindPercent());
        assertEquals(25_270, current.progress().remainingToNext());
    }

    @Test
    void keepsAllThreeLabeledTargetSlotsAtTheCap() {
        var service = new AccountLuckService(null, MagicFindProgression.canonical());
        var status = service.resolve(new AccountLuck("account-guid-a", 4_295_450,
                Instant.parse("2026-09-29T00:00:00Z")));
        assertEquals(300, status.progress().magicFindPercent());
        assertEquals(4_295_450, status.progress().currentThreshold());
        assertNull(status.progress().nextThreshold());
        assertEquals(0, status.progress().remainingToCap());
        assertEquals(3, status.targets().size());
        assertTrue(status.targets().stream().allMatch(target -> target.magicFindPercent() == 300));
        assertTrue(status.targets().stream().allMatch(target -> target.remainingLuck() == 0));
    }

    @Test
    void resolvesOneAccountWithNextFiveTenAndCapTargets() throws Exception {
        var scopeA = model.AccountScope.of("11111111-1111-1111-1111-111111111111");
        var scopeB = model.AccountScope.of("22222222-2222-2222-2222-222222222222");
        var sample = new AccountLuck(scopeA.accountId().toString(), 14_500, Instant.parse("2026-09-29T00:00:00Z"));
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(model.AccountScope scope) {
                return scope.equals(scopeA) ? Optional.of(sample) : Optional.empty();
            }
        };
        var service = new AccountLuckService(repository, MagicFindProgression.canonical());
        var status = service.getStatus(scopeA).orElseThrow();

        assertEquals(50, status.progress().magicFindPercent());
        assertEquals(13_790, status.progress().currentThreshold());
        assertEquals(50, status.progress().remainingToNext());
        assertEquals(4_295_450 - 14_500, status.progress().remainingToCap());
        assertEquals(3, status.targets().size());
        assertEquals(55, status.targets().get(0).magicFindPercent());
        assertEquals(MagicFindProgression.canonical().thresholdFor(55), status.targets().get(0).cumulativeLuck());
        assertEquals(60, status.targets().get(1).magicFindPercent());
        assertEquals(300, status.targets().get(2).magicFindPercent());
        var custom = service.resolve(sample, 51, 75);
        assertEquals(MagicFindProgression.canonical().thresholdFor(75), custom.targets().get(1).cumulativeLuck());
        assertEquals(custom.targets().get(1).cumulativeLuck() - sample.consumedLuck(),
                custom.targets().get(1).remainingLuck());
        assertTrue(service.getStatus(scopeB).isEmpty());
    }

    @Test
    void repositoryFailureBecomesDataStoreException() {
        var failure = new java.sql.SQLException("simulated luck read failure");
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(model.AccountScope scope) throws java.sql.SQLException {
                throw failure;
            }
        };
        var service = new AccountLuckService(repository, MagicFindProgression.canonical());
        var scope = model.AccountScope.of("11111111-1111-1111-1111-111111111111");
        var thrown = assertThrows(DataStoreException.class, () -> service.getStatus(scope));
        assertSame(failure, thrown.getCause());
    }
}
