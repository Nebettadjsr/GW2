package application;

import org.junit.jupiter.api.Test;
import repo.CharacterRepository;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (ARCHITECTURE.md section 2, STORY-APP-010) for
 * {@link CharacterSelectionService}: a fake {@link CharacterRepository} replaces the live
 * PostgreSQL read, so these run without a database. They prove scope delegation, the returned rows
 * and their order, the empty result and failure propagation - not the SQL itself (that stays unchanged
 * in {@code repo.CharacterRepository}).
 */
class CharacterSelectionServiceTest {

    private static final model.AccountScope SCOPE = model.AccountScope.of("11111111-1111-1111-1111-111111111111");
    private static final model.AccountScope OTHER = model.AccountScope.of("22222222-2222-2222-2222-222222222222");

    @Test
    void craftingOptionsAreTheScopedRepositoryRowsInRepositoryOrder() throws Exception {
        var repo = new FakeCharacterRepository();
        repo.cannedCrafting = List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true),
                new CharacterRepository.DiscRow("Bea", "Chef", 275, false),
                new CharacterRepository.DiscRow("Alice", "Tailor", 150, false));

        List<CharacterRepository.DiscRow> rows =
                new CharacterSelectionService(repo).getCraftingCharacterOptions(SCOPE);

        assertEquals(repo.cannedCrafting, rows);
        assertEquals(1, repo.craftingCallCount);
        assertEquals(List.of(SCOPE), repo.scopes, "the caller's scope, and no other account, reaches the read");
        new CharacterSelectionService(repo).getCraftingCharacterOptions(OTHER);
        assertEquals(List.of(SCOPE, OTHER), repo.scopes);
    }

    @Test
    void nothingSyncedReturnsAnEmptyListRatherThanFailing() throws Exception {
        var repo = new FakeCharacterRepository();

        var service = new CharacterSelectionService(repo);

        assertTrue(service.getCraftingCharacterOptions(SCOPE).isEmpty());
    }

    @Test
    void craftingReadFailureBecomesDataStoreException() {
        var repo = new FakeCharacterRepository();
        repo.craftingFailure = new SQLException("simulated character crafting read failure");

        DataStoreException thrown = assertThrows(DataStoreException.class,
                () -> new CharacterSelectionService(repo).getCraftingCharacterOptions(SCOPE));
        assertSame(repo.craftingFailure, thrown.getCause());
    }

    private static class FakeCharacterRepository extends CharacterRepository {
        List<DiscRow> cannedCrafting = List.of();
        SQLException craftingFailure;
        int craftingCallCount = 0;
        final List<model.AccountScope> scopes = new java.util.ArrayList<>();

        @Override
        public List<DiscRow> loadAllCharacterCrafting(model.AccountScope scope) throws SQLException {
            craftingCallCount++;
            scopes.add(scope);
            if (craftingFailure != null) throw craftingFailure;
            return cannedCrafting;
        }
    }
}
