package application;

import org.junit.jupiter.api.Test;
import repo.BankRepository;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (ARCHITECTURE.md section 2, STORY-APP-009) for
 * {@link BankContentsService}: a fake {@link BankRepository} replaces the live PostgreSQL read, so
 * these run without a database. They prove delegation, returned rows, the empty result and
 * failure propagation - not the SQL itself (that is covered by {@code repo.BankRepositoryTest}).
 */
class BankContentsServiceTest {

    private static final model.AccountScope SCOPE = model.AccountScope.of("11111111-1111-1111-1111-111111111111");
    private static final model.AccountScope OTHER = model.AccountScope.of("22222222-2222-2222-2222-222222222222");

    @Test
    void returnsTheRepositoryRowsInOrderAndDelegatesExactlyOnce() throws Exception {
        var repo = new FakeBankRepository();
        repo.canned = List.of(
                new BankRepository.BankSlotRow(0, 19721, 5, null, "Rare"),
                new BankRepository.BankSlotRow(1, null, null, null, null),
                new BankRepository.BankSlotRow(2, 24277, 250, null, "Basic"));

        List<BankRepository.BankSlotRow> slots = new BankContentsService(repo).getBankContents(SCOPE);

        assertEquals(repo.canned, slots);
        assertEquals(1, repo.callCount);
        assertEquals(List.of(SCOPE), repo.scopes, "the caller's scope, and no other account, reaches the read");
        new BankContentsService(repo).getBankContents(OTHER);
        assertEquals(List.of(SCOPE, OTHER), repo.scopes);
    }

    @Test
    void emptyBankReturnsAnEmptyListRatherThanFailing() throws Exception {
        var repo = new FakeBankRepository();
        repo.canned = List.of();

        assertTrue(new BankContentsService(repo).getBankContents(SCOPE).isEmpty());
        assertEquals(1, repo.callCount);
    }

    @Test
    void repositoryFailureBecomesDataStoreException() {
        var repo = new FakeBankRepository();
        repo.failure = new SQLException("simulated bank read failure");

        DataStoreException thrown = assertThrows(DataStoreException.class,
                () -> new BankContentsService(repo).getBankContents(SCOPE));
        assertSame(repo.failure, thrown.getCause());
    }

    private static class FakeBankRepository extends BankRepository {
        List<BankSlotRow> canned = List.of();
        SQLException failure;
        int callCount = 0;
        final List<model.AccountScope> scopes = new java.util.ArrayList<>();

        @Override
        public List<BankSlotRow> loadBankSlots(model.AccountScope scope) throws SQLException {
            callCount++;
            scopes.add(scope);
            if (failure != null) throw failure;
            return canned;
        }
    }
}
