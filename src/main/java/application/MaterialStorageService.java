package application;

import repo.MaterialStorageRepository;
import repo.MaterialStorageRepository.MaterialStorageRow;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-layer use case for the Materials view's read ("GetMaterialStorage",
 * TARGET_ARCHITECTURE.md §8, STORY-APP-009): returns every non-empty material stack grouped into
 * the in-game material categories, in the established category order. Holds no JavaFX dependency
 * and performs no calculation; it coordinates one persistence collaborator and reproduces the
 * grouping {@code MaterialsView} previously did inline around its own JDBC loop.
 *
 * <p>Read-only: no synchronization and no database write happens here or in
 * {@link MaterialStorageRepository}. A {@link SQLException} propagates unchanged, so the view keeps
 * deciding how to present a load failure.
 */
public class MaterialStorageService {

    /**
     * Category id to category name, in the order the categories are presented. Moved verbatim out
     * of {@code MaterialsView.materialCategoryNames()}; a category id outside this map falls back
     * to {@link #UNKNOWN_CATEGORY_PREFIX} + id, which is how an unexpected id becomes visible.
     */
    private static final Map<Integer, String> CATEGORY_NAMES = categoryNames();

    private static final String UNKNOWN_CATEGORY_PREFIX = "Category ";

    private final MaterialStorageRepository materialRepo;

    public MaterialStorageService() {
        this(new MaterialStorageRepository());
    }

    /** Seam used by application-layer tests to substitute a fake repository (TARGET_ARCHITECTURE.md §25). */
    public MaterialStorageService(MaterialStorageRepository materialRepo) {
        this.materialRepo = materialRepo;
    }

    /** One material category and its stacks, in the order the repository returned them. */
    public record MaterialCategory(String name, List<MaterialStorageRow> materials) {}

    /**
     * Non-empty material stacks grouped by category: known categories first, in
     * {@link #CATEGORY_NAMES} order, then any unknown category in first-seen order. Categories with
     * no stacks are omitted, so the result is empty when material storage is empty.
     */
    public List<MaterialCategory> getMaterialStorage() throws SQLException {
        List<MaterialStorageRow> rows = materialRepo.loadMaterialStorage();

        LinkedHashMap<String, List<MaterialStorageRow>> grouped = new LinkedHashMap<>();
        for (String name : CATEGORY_NAMES.values()) grouped.put(name, new ArrayList<>());

        for (MaterialStorageRow row : rows) {
            String group = CATEGORY_NAMES.getOrDefault(row.category(), UNKNOWN_CATEGORY_PREFIX + row.category());
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(row);
        }

        grouped.entrySet().removeIf(e -> e.getValue().isEmpty());

        List<MaterialCategory> out = new ArrayList<>();
        grouped.forEach((name, materials) -> out.add(new MaterialCategory(name, materials)));
        return out;
    }

    private static Map<Integer, String> categoryNames() {
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        m.put(1,  "Basic Crafting Materials");
        m.put(2,  "Intermediate Crafting Materials");
        m.put(3,  "Advanced Crafting Materials");
        m.put(4,  "Ascended Materials");
        m.put(5,  "Cooking Materials");
        m.put(6,  "Cooking Ingredients");
        m.put(7,  "Scribing Materials");
        m.put(8,  "Festive Materials");
        m.put(9,  "Guild Materials");
        m.put(10, "Other");
        return Collections.unmodifiableMap(m);
    }
}
