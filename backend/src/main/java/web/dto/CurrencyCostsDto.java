package web.dto;

import java.util.List;

/**
 * Typed currency spending of a recipe, kept apart from item rows and from any gold valuation.
 *
 * @param walletState        {@code KNOWN} (balance available), {@code UNKNOWN} (no permission, never refreshed
 *                           or failed: neither zero nor unlimited)
 * @param goldMarginIsConditional always true here: {@code profitCopper} is the known gold margin after
 *                           Trading Post fees and known material costs, <em>before</em> the unpriced currencies
 * @param economicsFromHypotheticalCraft the wallet blocked every real craft, so the per-craft figures describe
 *                           one hypothetical craft (the output quote and material costs stay valid)
 */
public record CurrencyCostsDto(String walletState,
                               boolean goldMarginIsConditional,
                               boolean economicsFromHypotheticalCraft,
                               List<CurrencyCostDto> costs) {

    /**
     * @param perCraft  exact units one craft consumes (including nested crafts)
     * @param total     exact units the counted crafts consume
     * @param breakEvenCopperPerUnit known gold margin per craft divided by {@code perCraft}, only when this is the
     *                  single unpriced currency and the margin is positive; otherwise null
     */
    public record CurrencyCostDto(int currencyId, String name, int perCraft, int total, Integer breakEvenCopperPerUnit) {}
}
