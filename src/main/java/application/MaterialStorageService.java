package application;

import repo.MaterialStorageRepository;
import repo.MaterialStorageRepository.MaterialStorageRow;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only material-storage use case; it never starts an account synchronization. */
public class MaterialStorageService {
    private final MaterialStorageRepository materialRepo;
    public MaterialStorageService() { this(new MaterialStorageRepository()); }
    public MaterialStorageService(MaterialStorageRepository materialRepo) { this.materialRepo = materialRepo; }

    public record MaterialCategory(int id, String name, int order, List<MaterialStorageRow> materials) {
        public MaterialCategory(String name, List<MaterialStorageRow> materials) { this(-1, name, -1, materials); }
    }

    public List<MaterialCategory> getMaterialStorage() throws SQLException {
        List<MaterialStorageRow> rows = materialRepo.loadMaterialStorage();
        Map<Integer, List<MaterialStorageRow>> grouped = new LinkedHashMap<>();
        for (MaterialStorageRow row : rows) grouped.computeIfAbsent(row.category(), ignored -> new ArrayList<>()).add(row);
        List<MaterialCategory> categories = new ArrayList<>();
        grouped.forEach((id, materials) -> {
            MaterialStorageRow first = materials.getFirst();
            List<MaterialStorageRow> positions = materials.stream().filter(row -> row.itemId() != null).toList();
            categories.add(new MaterialCategory(id, first.categoryName(), first.categoryOrder(), positions));
        });
        return List.copyOf(categories);
    }
}
