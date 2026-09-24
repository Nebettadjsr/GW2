package application;

import repo.BankRepository;

import java.sql.SQLException;
import java.util.List;

/**
 * Application-layer use case for the Bank view's read ("GetBankContents",
 * TARGET_ARCHITECTURE.md §8, STORY-APP-009): returns every account-bank slot, in slot order, with
 * the item/icon/rarity columns the view renders. Holds no JavaFX dependency and performs no
 * calculation; it coordinates one persistence collaborator. {@code BankView} is its sole caller.
 *
 * <p>Read-only: no synchronization and no database write happens here or in
 * {@link BankRepository}. A {@link SQLException} propagates unchanged, so the view keeps deciding
 * how to present a load failure.
 */
public class BankContentsService {

    private final BankRepository bankRepo;

    public BankContentsService() {
        this(new BankRepository());
    }

    /** Seam used by application-layer tests to substitute a fake repository (TARGET_ARCHITECTURE.md §25). */
    public BankContentsService(BankRepository bankRepo) {
        this.bankRepo = bankRepo;
    }

    /** Every bank slot ordered by slot number; empty when the account has no bank rows. */
    public List<BankRepository.BankSlotRow> getBankContents() throws SQLException {
        return bankRepo.loadBankSlots();
    }
}
