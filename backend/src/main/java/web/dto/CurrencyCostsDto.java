package web.dto;

import java.util.List;

/**
 * Typed currency spending of a recipe, kept apart from item rows and from any gold valuation.
 *
 * @param walletState        {@code KNOWN} (balance available), {@code UNKNOWN} (no permission, never refreshed
 *                           or failed: neither zero nor unlimited)
 * @param goldMarginIsConditional true while a consumed currency stays unpriced; {@code profitCopper} is then the known gold margin after
 *                           Trading Post fees and known material costs, <em>before</em> the unpriced currencies
 * @param economicsFromHypotheticalCraft the wallet blocked every real craft, so the per-craft figures describe
 *                           one hypothetical craft (the output quote and material costs stay valid)
 */
public record CurrencyCostsDto(String walletState,
                               boolean goldMarginIsConditional,
                               boolean economicsFromHypotheticalCraft,
                               List<CurrencyCostDto> costs,
                               NoteEstimateDto estimate) {

    public CurrencyCostsDto(String walletState, boolean goldMarginIsConditional,
                            boolean economicsFromHypotheticalCraft, List<CurrencyCostDto> costs) {
        this(walletState, goldMarginIsConditional, economicsFromHypotheticalCraft, costs, null);
    }

    /**
     * Approved Research Note estimate (DOMAIN_SPEC.md 2.1.0), present only while wallet Notes were valued: the Notes
     * paid from the existing wallet and the whole-copper amount the backend already deducted from profit.
     * {@code variants} (both actual recipes) is selected-detail data only and empty in table rows.
     */
    public record NoteEstimateDto(int perCraftNotes, int totalNotes, int perCraftCopper, int totalCopper,
                                  Integer basisRecipeId, List<VariantDto> variants) {}

    public record VariantDto(int recipeId, String outputName, int notesPerBatch, Long batchCostCopper,
                             Long perNoteCopperCeil, boolean basis, List<LineDto> ingredients) {}

    public record LineDto(int itemId, String name, int count, Integer unitPriceCopper, Long totalCopper,
                          String iconUrl) {}

    /**
     * @param perCraft  exact units one craft consumes (including nested crafts)
     * @param total     exact units the counted crafts consume
     */
    public record CurrencyCostDto(int currencyId, String name, int perCraft, int total) {}
}
