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
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-009) for
 * {@link BankContentsService}: a fake {@link BankRepository} replaces the live PostgreSQL read, so
 * these run without a database. They prove delegation, returned rows, the empty result and
 * failure propagation - not the SQL itself (that is covered by {@code repo.BankRepositoryTest}).
 */
class BankContentsServiceTest {

    @Test
    void returnsTheRepositoryRowsInOrderAndDelegatesExactlyOnce() throws Exception {
        var repo = new FakeBankRepository();
        repo.canned = List.of(
                new BankRepository.BankSlotRow(0, 19721, 5, "C:\\icons\\19721.png", "Rare"),
                new BankRepository.BankSlotRow(1, null, null, null, null),
                new BankRepository.BankSlotRow(2, 24277, 250, "C:\\icons\\24277.png", "Basic"));

        List<BankRepository.BankSlotRow> slots = new BankContentsService(repo).getBankContents();

        assertEquals(repo.canned, slots);
        assertEquals(1, repo.callCount);
    }

    @Test
    void emptyBankReturnsAnEmptyListRatherThanFailing() throws Exception {
        var repo = new FakeBankRepository();
        repo.canned = List.of();

        assertTrue(new BankContentsService(repo).getBankContents().isEmpty());
        assertEquals(1, repo.callCount);
    }

    @Test
    void repositoryFailurePropagatesUnchanged() {
        var repo = new FakeBankRepository();
        repo.failure = new SQLException("simulated bank read failure");

        SQLException thrown = assertThrows(SQLException.class,
                () -> new BankContentsService(repo).getBankContents());
        assertSame(repo.failure, thrown);
    }

    private static class FakeBankRepository extends BankRepository {
        List<BankSlotRow> canned = List.of();
        SQLException failure;
        int callCount = 0;

        @Override
        public List<BankSlotRow> loadBankSlots() throws SQLException {
            callCount++;
            if (failure != null) throw failure;
            return canned;
        }
    }
}
