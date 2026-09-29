package application;

import luck.MagicFindProgression;
import model.AccountLuck;
import repo.AccountLuckRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Resolves stored account Luck against the single global progression. */
public class AccountLuckService {
    private final AccountLuckRepository repository;
    private final MagicFindProgression progression;

    public AccountLuckService() { this(new AccountLuckRepository(), MagicFindProgression.canonical()); }

    public AccountLuckService(AccountLuckRepository repository, MagicFindProgression progression) {
        this.repository = repository;
        this.progression = progression;
    }

    public Optional<AccountLuckStatus> getStatus(String accountId) throws SQLException {
        return repository.find(accountId).map(this::resolve);
    }

    public Optional<AccountLuckStatus> getStatus(String accountId, int... targetPercents) throws SQLException {
        return repository.find(accountId).map(luck -> resolve(luck, targetPercents));
    }

    public AccountLuckStatus resolve(AccountLuck luck) {
        var progress = progression.resolve(luck.consumedLuck());
        int current = progress.magicFindPercent();
        return resolve(luck, Math.min(300, current + 5), Math.min(300, current + 10), 300);
    }

    /** Allows a later client to request specific targets without receiving the whole table. */
    public AccountLuckStatus resolve(AccountLuck luck, int... targetPercents) {
        var progress = progression.resolve(luck.consumedLuck());
        var targets = new ArrayList<Target>();
        for (int target : targetPercents) {
            if (target < 0 || target > 300 || target < progress.magicFindPercent()
                    || (target == progress.magicFindPercent() && target < 300)) {
                throw new IllegalArgumentException("Target must exceed current Magic Find");
            }
            targets.add(new Target(target, progression.thresholdFor(target),
                    progression.remainingFor(luck.consumedLuck(), target)));
        }
        return new AccountLuckStatus(luck.accountId(), luck.fetchedAt(), progress,
                progression.thresholdFor(MagicFindProgression.CAP_PERCENT), List.copyOf(targets));
    }

    public record Target(int magicFindPercent, long cumulativeLuck, long remainingLuck) {}

    public record AccountLuckStatus(String accountId, Instant fetchedAt,
                                    MagicFindProgression.Progress progress, long capThreshold,
                                    List<Target> targets) {}
}
