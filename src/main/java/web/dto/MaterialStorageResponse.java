package web.dto;

import java.util.List;

/**
 * Transport response body for {@code GET /api/account/materials} (STORY-API-007,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Carries account material storage exactly as
 * {@code application.MaterialStorageService.getMaterialStorage()} grouped it: the categories that
 * have stacks, in the service's category order, each holding its stacks in the service's order. The
 * grouping, the category labels, the {@code "Category <id>"} fallback label and the
 * "non-empty stacks only" inclusion rule are all the service's; nothing here re-derives them.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 * {@code materials} is a copy of the service's rows, not a serialized
 * {@code repo.MaterialStorageRepository.MaterialStorageRow}.
 *
 * @param categoryCount size of {@code categories}, so a caller can tell an empty result from a
 *                      truncated one without counting
 * @param categories    the categories that have at least one stack, in the service's order; empty
 *                      when material storage holds nothing
 */
public record MaterialStorageResponse(int categoryCount, List<MaterialCategoryDto> categories) {

    /**
     * One material category and its stacks, as grouped by
     * {@code application.MaterialStorageService.getMaterialStorage()}.
     *
     * @param name      the category's display label, or the service's {@code "Category <id>"}
     *                  fallback for an id it does not know; never null
     * @param materials the category's stacks in the service's order; never empty, since the service
     *                  omits categories that have no stacks
     */
    public record MaterialCategoryDto(String name, List<MaterialStackDto> materials) {
    }

    /**
     * One non-empty material stack within its category.
     *
     * @param category the numeric category id the stack was grouped by; reported because it is the
     *                 only way a caller can tell which id produced a fallback label
     * @param itemId   the stack's item; null when the stored row carries no item id
     * @param count    stack size; always present, since the service's read excludes empty stacks
     * @param iconUrl  this application's own image URL for the stack's item
     *                 (TARGET_ARCHITECTURE.md §12.1), or null when there is no item, no matching item
     *                 row, or no accepted retained source. Never a filesystem path or upstream URL
     * @param rarity   display metadata; null when the stack's item has no matching item row
     */
    public record MaterialStackDto(int category,
                                   Integer itemId,
                                   int count,
                                   String iconUrl,
                                   String rarity) {
    }
}
