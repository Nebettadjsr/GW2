package web.dto;

import java.util.List;

/**
 * Transport response body for {@code GET /api/account/bank} (STORY-API-007,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Carries the account bank exactly as {@code application.BankContentsService.getBankContents()}
 * reports it: every slot, in slot order, including the empty ones. Empty slots are kept rather than
 * dropped because the bank is a grid - removing them would shift every following item into the
 * wrong cell, and the caller cannot restore the layout from a compacted list.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 * {@code slots} is a copy of the service's rows, not a serialized
 * {@code repo.BankRepository.BankSlotRow}.
 *
 * @param slotCount size of {@code slots}, so a caller can tell an empty result from a truncated one
 *                  without counting
 * @param slots     every bank slot in the service's slot order; empty when the account has no bank
 *                  rows at all
 */
public record BankContentsResponse(int slotCount, List<BankSlotDto> slots) {

    /**
     * One account-bank slot, as reported by
     * {@code application.BankContentsService.getBankContents()}.
     *
     * @param slot    the slot number the row carries; the list's order is this field's order
     * @param itemId  null for an empty slot - that is how an empty slot is represented, and no
     *                substitute id is invented
     * @param count   stack size; null for an empty slot
     * @param iconUrl this application's own image URL for the slot's item
     *                (TARGET_ARCHITECTURE.md §12.1), or null when the slot is empty, the item has no
     *                matching item row, or its retained metadata is absent or not an accepted source.
     *                Never a filesystem path and never an upstream URL: the backend's filesystem
     *                {@code iconPath} deliberately left this contract with STORY-API-009
     * @param rarity  display metadata; null when the slot's item has no matching item row
     */
    public record BankSlotDto(int slot,
                              Integer itemId,
                              Integer count,
                              String iconUrl,
                              String rarity) {
    }
}
