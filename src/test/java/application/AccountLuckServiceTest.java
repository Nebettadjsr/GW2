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
        var sample = new AccountLuck("account-guid-a", 14_500, Instant.parse("2026-09-29T00:00:00Z"));
        var repository = new AccountLuckRepository() {
            @Override public Optional<AccountLuck> find(String accountId) {
                return accountId.equals(sample.accountId()) ? Optional.of(sample) : Optional.empty();
            }
        };
        var service = new AccountLuckService(repository, MagicFindProgression.canonical());
        var status = service.getStatus("account-guid-a").orElseThrow();

        assertEquals(50, status.progress().magicFindPercent());
        assertEquals(13_790, status.progress().currentThreshold());
        assertEquals(50, status.progress().remainingToNext());
        assertEquals(4_295_450 - 14_500, status.progress().remainingToCap());
        assertEquals(3, status.targets().size());
        assertEquals(55, status.targets().get(0).magicFindPercent());
        assertEquals(MagicFindProgression.canonical().thresholdFor(55), status.targets().get(0).cumulativeLuck());
        assertEquals(60, status.targets().get(1).magicFindPercent());
        assertEquals(300, status.targets().get(2).magicFindPercent());
        var custom = service.getStatus("account-guid-a", 51, 75).orElseThrow();
        assertEquals(MagicFindProgression.canonical().thresholdFor(75), custom.targets().get(1).cumulativeLuck());
        assertEquals(custom.targets().get(1).cumulativeLuck() - sample.consumedLuck(),
                custom.targets().get(1).remainingLuck());
        assertTrue(service.getStatus("account-guid-b").isEmpty());
    }
}
