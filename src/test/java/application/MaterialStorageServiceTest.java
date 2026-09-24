package application;

import application.MaterialStorageService.MaterialCategory;
import org.junit.jupiter.api.Test;
import repo.MaterialStorageRepository;
import repo.MaterialStorageRepository.MaterialStorageRow;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-009) for
 * {@link MaterialStorageService}: a fake {@link MaterialStorageRepository} replaces the live
 * PostgreSQL read, so these run without a database. They pin the grouping/ordering/empty-category
 * behavior moved verbatim out of {@code MaterialsView}, plus failure propagation - not the SQL
 * itself (that is covered by {@code repo.MaterialStorageRepositoryTest}).
 */
class MaterialStorageServiceTest {

    @Test
    void groupsStacksByCategoryNameInTheEstablishedCategoryOrder() throws Exception {
        var repo = new FakeMaterialStorageRepository();
        // Deliberately not in category order: the presented order must come from the category list,
        // not from the row order.
        repo.canned = List.of(
                row(5, 12142, 3),
                row(1, 19697, 100),
                row(1, 19719, 40));

        List<MaterialCategory> categories = new MaterialStorageService(repo).getMaterialStorage();

        assertEquals(List.of("Basic Crafting Materials", "Cooking Materials"), names(categories));
        assertEquals(List.of(19697, 19719), itemIds(categories.get(0)));
        assertEquals(List.of(12142), itemIds(categories.get(1)));
        assertEquals(1, repo.callCount);
    }

    @Test
    void categoriesWithNoStacksAreOmitted() throws Exception {
        var repo = new FakeMaterialStorageRepository();
        repo.canned = List.of(row(4, 46731, 2));

        List<MaterialCategory> categories = new MaterialStorageService(repo).getMaterialStorage();

        assertEquals(List.of("Ascended Materials"), names(categories));
    }

    @Test
    void unknownCategoryIdIsGroupedUnderItsNumberAfterTheKnownCategories() throws Exception {
        var repo = new FakeMaterialStorageRepository();
        repo.canned = List.of(
                row(12, 99999, 1),
                row(10, 24272, 7));

        List<MaterialCategory> categories = new MaterialStorageService(repo).getMaterialStorage();

        assertEquals(List.of("Other", "Category 12"), names(categories));
        assertEquals(List.of(99999), itemIds(categories.get(1)));
    }

    @Test
    void emptyMaterialStorageReturnsNoCategories() throws Exception {
        var repo = new FakeMaterialStorageRepository();
        repo.canned = List.of();

        assertTrue(new MaterialStorageService(repo).getMaterialStorage().isEmpty());
        assertEquals(1, repo.callCount);
    }

    @Test
    void repositoryFailurePropagatesUnchanged() {
        var repo = new FakeMaterialStorageRepository();
        repo.failure = new SQLException("simulated material storage read failure");

        SQLException thrown = assertThrows(SQLException.class,
                () -> new MaterialStorageService(repo).getMaterialStorage());
        assertSame(repo.failure, thrown);
    }

    private static MaterialStorageRow row(int category, int itemId, int count) {
        return new MaterialStorageRow(category, itemId, count, "C:\\icons\\" + itemId + ".png", "Basic");
    }

    private static List<String> names(List<MaterialCategory> categories) {
        return categories.stream().map(MaterialCategory::name).toList();
    }

    private static List<Integer> itemIds(MaterialCategory category) {
        return category.materials().stream().map(MaterialStorageRow::itemId).toList();
    }

    private static class FakeMaterialStorageRepository extends MaterialStorageRepository {
        List<MaterialStorageRow> canned = List.of();
        SQLException failure;
        int callCount = 0;

        @Override
        public List<MaterialStorageRow> loadMaterialStorage() throws SQLException {
            callCount++;
            if (failure != null) throw failure;
            return canned;
        }
    }
}
