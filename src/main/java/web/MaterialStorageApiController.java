package web;

import application.MaterialStorageService;
import application.icons.ItemIconUrls;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.MaterialStorageRepository.MaterialStorageRow;
import web.dto.MaterialStorageResponse;

import java.sql.SQLException;
import java.util.List;

/**
 * HTTP boundary for the account material-storage read (STORY-API-007, TARGET_ARCHITECTURE.md §9):
 * ordered official material positions joined with the most recently synchronized account quantities.
 *
 * <p>Thin by construction: it calls the existing {@link MaterialStorageService} read
 * {@code MaterialsView} uses (§5.12/§6 of CURRENT_ARCHITECTURE.md) and copies the grouped result
 * into transport records. The category order and official item positions come from persisted
 * catalog data and are not reproduced here; the
 * controller queries no repository, aggregates nothing and synchronizes nothing.
 *
 * <p>Like the selector-options route it holds a single shared service instance: the service keeps no
 * per-call state, so there is no read result for concurrent requests to observe.
 */
@RestController
@RequestMapping("/api/account")
public class MaterialStorageApiController {

    private final MaterialStorageService materialStorageService;

    public MaterialStorageApiController(MaterialStorageService materialStorageService) {
        this.materialStorageService = materialStorageService;
    }

    /**
     * Returns every synchronized official material category and catalog position.
     *
     * <p>Status contract: 200 with the categories; 503 when the database is unavailable; 500 when
     * the read fails for any other reason. See {@link AccountReadApiExceptionHandler}. Empty
     * material storage is a 200 with all catalog positions at count zero. Before the first
     * successful account-material sync the read fails instead of presenting an empty inventory.
     */
    @GetMapping(path = "/materials", produces = MediaType.APPLICATION_JSON_VALUE)
    public MaterialStorageResponse materialStorage() throws SQLException {
        List<MaterialStorageResponse.MaterialCategoryDto> categories =
                toCategories(materialStorageService.getMaterialStorage());

        return new MaterialStorageResponse(categories.size(), categories);
    }

    /** Field-for-field copy, preserving the service's grouping and order; no regrouping or sorting. */
    private static List<MaterialStorageResponse.MaterialCategoryDto> toCategories(
            List<MaterialStorageService.MaterialCategory> categories) {

        return categories.stream()
                .map(category -> new MaterialStorageResponse.MaterialCategoryDto(
                        category.id(), category.name(), category.order(), toStacks(category.materials())))
                .toList();
    }

    /**
     * The one derived value is {@code iconUrl}: this application's image URL for the stack's item,
     * computed from the retained source the same batch read already carried
     * (TARGET_ARCHITECTURE.md §12.1). No lookup, no request and no calculation happens per stack, and
     * the backend's local {@code iconPath} stays out of the response.
     */
    private static List<MaterialStorageResponse.MaterialStackDto> toStacks(List<MaterialStorageRow> rows) {
        return rows.stream()
                .map(row -> new MaterialStorageResponse.MaterialStackDto(
                        row.position(),
                        row.itemId(),
                        row.count(),
                        ItemIconUrls.iconUrlFor(row.itemId(), row.iconUrl()),
                        row.rarity()))
                .toList();
    }
}
