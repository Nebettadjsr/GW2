package web.dto;

import java.util.List;

/** Ordered official material catalog combined with synchronized quantities. */
public record MaterialStorageResponse(Integer categoryCount, List<MaterialCategoryDto> categories,
                                      AccountSourceFreshnessDto freshness) {
    public record MaterialCategoryDto(int category, String name, int order, List<MaterialStackDto> materials) {}
    public record MaterialStackDto(int position, int itemId, int count, String iconUrl, String rarity) {}
}
