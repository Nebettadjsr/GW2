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
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-010) for
 * {@link CharacterSelectionService}: a fake {@link CharacterRepository} replaces the live
 * PostgreSQL reads, so these run without a database. They prove delegation, the returned rows and
 * their order, the empty result and failure propagation - not the SQL itself (that stays unchanged
 * in {@code repo.CharacterRepository}).
 */
class CharacterSelectionServiceTest {

    @Test
    void craftingOptionsAreTheRepositoryRowsInRepositoryOrder() throws Exception {
        var repo = new FakeCharacterRepository();
        repo.cannedCrafting = List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true),
                new CharacterRepository.DiscRow("Bea", "Chef", 275, false),
                new CharacterRepository.DiscRow("Alice", "Tailor", 150, false));

        List<CharacterRepository.DiscRow> rows =
                new CharacterSelectionService(repo).getCraftingCharacterOptions();

        assertEquals(repo.cannedCrafting, rows);
        assertEquals(1, repo.craftingCallCount);
        assertEquals(0, repo.namesCallCount, "the crafting read must not also load names");
    }

    @Test
    void characterNamesAreTheRepositoryNamesInRepositoryOrder() throws Exception {
        var repo = new FakeCharacterRepository();
        repo.cannedNames = List.of("Alice", "Bea", "Cyn");

        List<String> names = new CharacterSelectionService(repo).getCharacterNames();

        assertEquals(List.of("Alice", "Bea", "Cyn"), names);
        assertEquals(1, repo.namesCallCount);
        assertEquals(0, repo.craftingCallCount, "the name read must not also load crafting rows");
    }

    @Test
    void nothingSyncedReturnsEmptyListsRatherThanFailing() throws Exception {
        var repo = new FakeCharacterRepository();

        var service = new CharacterSelectionService(repo);

        assertTrue(service.getCraftingCharacterOptions().isEmpty());
        assertTrue(service.getCharacterNames().isEmpty());
    }

    @Test
    void craftingReadFailurePropagatesUnchanged() {
        var repo = new FakeCharacterRepository();
        repo.craftingFailure = new SQLException("simulated character crafting read failure");

        SQLException thrown = assertThrows(SQLException.class,
                () -> new CharacterSelectionService(repo).getCraftingCharacterOptions());
        assertSame(repo.craftingFailure, thrown);
    }

    @Test
    void nameReadFailurePropagatesUnchanged() {
        var repo = new FakeCharacterRepository();
        repo.namesFailure = new SQLException("simulated character name read failure");

        SQLException thrown = assertThrows(SQLException.class,
                () -> new CharacterSelectionService(repo).getCharacterNames());
        assertSame(repo.namesFailure, thrown);
    }

    private static class FakeCharacterRepository extends CharacterRepository {
        List<DiscRow> cannedCrafting = List.of();
        List<String> cannedNames = List.of();
        SQLException craftingFailure;
        SQLException namesFailure;
        int craftingCallCount = 0;
        int namesCallCount = 0;

        @Override
        public List<DiscRow> loadAllCharacterCrafting() throws SQLException {
            craftingCallCount++;
            if (craftingFailure != null) throw craftingFailure;
            return cannedCrafting;
        }

        @Override
        public List<String> loadAllCharacterNames() throws SQLException {
            namesCallCount++;
            if (namesFailure != null) throw namesFailure;
            return cannedNames;
        }
    }
}
