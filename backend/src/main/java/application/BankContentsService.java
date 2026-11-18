package application;

import model.AccountScope;
import repo.BankRepository;

import java.sql.SQLException;
import java.util.List;

/**
 * Bank read use case: returns every account-bank slot, in slot order, with the item/icon/rarity
 * columns the Bank API renders. It performs no calculation and coordinates one persistence
 * collaborator; {@code BankContentsApiController} is its caller.
 *
 * <p>Read-only: no synchronization and no database write happens here or in
 * {@link BankRepository}. A {@link SQLException} propagates unchanged, so the caller keeps deciding
 * how to present a load failure.
 */
public class BankContentsService {

    private final BankRepository bankRepo;

    public BankContentsService() {
        this(new BankRepository());
    }

    /** Seam used by application-layer tests to substitute a fake repository. */
    public BankContentsService(BankRepository bankRepo) {
        this.bankRepo = bankRepo;
    }

    /**
     * One account's bank slots (STORY-APP-015). The scope is a required argument rather than
     * something this use case resolves: it is passed in by the boundary that authorized the request,
     * so there is no configured, last-used or default account it could fall back to.
     */
    public List<BankRepository.BankSlotRow> getBankContents(AccountScope scope) throws SQLException {
        return bankRepo.loadBankSlots(scope);
    }
}
