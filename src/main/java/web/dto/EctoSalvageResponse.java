package web.dto;

/**
 * Response body of the Ectoplasm Salvage calculation route (STORY-WEB-013): the four
 * Ecto-buy/Dust-sell scenarios {@code application.EctoSalvageService} produced for one live price
 * snapshot, plus the expected-value assumptions the domain calculated them with.
 *
 * <p>Every number here was produced by the domain and is copied field for field. Nothing is
 * recomputed, rounded, re-signed or defaulted at this boundary, and no caller is expected to derive
 * one field from another: a client that took {@code tradingPostSellFeePercent} off
 * {@code expectedGrossRecoveredDustValueCopper} would be running its own economics, which is exactly
 * what this contract exists to prevent (DOMAIN_SPEC.md §25, §46).
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param resultAvailable false when the Trading Post returned no usable quote for both Ecto and
 *                        Dust. That is a completed calculation with no result, not a failure: the
 *                        four scenario fields are then null, never zero (DOMAIN_SPEC.md §21)
 * @param ectoItemId      the item the acquisition costs were quoted for, as the application service
 *                        names it; supplied so a caller identifies the item rather than assuming one
 * @param dustItemId      the item the sale values were quoted for, likewise
 * @param assumptions     the expected-value parameters, always reported - they are domain constants
 *                        and do not depend on a price snapshot
 */
public record EctoSalvageResponse(
        boolean resultAvailable,
        int ectoItemId,
        int dustItemId,
        EctoSalvageAssumptionsDto assumptions,
        EctoScenarioDto instantBuyInstantSell,
        EctoScenarioDto instantBuyListingSell,
        EctoScenarioDto listingBuyInstantSell,
        EctoScenarioDto listingBuyListingSell) {

    /**
     * The salvage assumptions and the fee percentage, read from their domain owner
     * ({@code ecto.EctoSalvageCalculator}) rather than restated here.
     *
     * <p>They are expected values, not guaranteed drops (DOMAIN_SPEC.md §45, §47): a caller may
     * label them but must not present them as a promised salvage outcome.
     *
     * @param expectedLuckPerEcto        expected Luck recovered from salvaging one Ectoplasm
     * @param expectedDustPerEcto        expected Crystalline Dust recovered from one Ectoplasm; a
     *                                   fraction, and deliberately not rounded into whole drops
     * @param ectosPer1000Luck           the Ectoplasm count section 47's Luck cost is scaled by
     * @param tradingPostSellFeePercent  the selling fee already deducted, once, from the expected
     *                                   gross recovered Dust value in every scenario below
     *                                   (DOMAIN_SPEC.md §25). Reported so a caller can state it
     *                                   without holding a second copy of a domain number
     */
    public record EctoSalvageAssumptionsDto(
            double expectedLuckPerEcto,
            double expectedDustPerEcto,
            int ectosPer1000Luck,
            int tradingPostSellFeePercent) {
    }

    /**
     * One Ecto-buy / Dust-sell scenario, per single Ectoplasm except where stated.
     *
     * <p>Gross values and fee-inclusive economic results are kept apart deliberately (DOMAIN_SPEC.md
     * §25, §46): {@code ectoAcquisitionCostCopper}, {@code dustGrossUnitPriceCopper} and
     * {@code expectedGrossRecoveredDustValueCopper} carry no fee and are the ones to display as
     * prices, while the remaining fields are economic results the domain produced with the selling
     * fee applied exactly once, on the Dust side only.
     *
     * @param ectoAcquisitionCostCopper     gross quote paid per Ectoplasm; no fee applies to a purchase
     * @param dustGrossUnitPriceCopper      gross Dust quote for the scenario's sale mode
     * @param expectedGrossRecoveredDustValueCopper
     *                                      what the expected Dust yield of one Ectoplasm is worth at
     *                                      that gross quote, before any fee; a fractional expected
     *                                      yield is never reported as a whole modeled sale
     * @param netValueOfRecoveredDustCopper that same expected value after the fee
     * @param netCostPerEctoCopper          economic net cost per Ectoplasm; negative when the recovered
     *                                      Dust is worth more than the Ectoplasm cost
     * @param profitPerEctoCopper           economic profit per Ectoplasm, the sign carrying gain or loss
     * @param costPer1000LuckCopper         section 47's economic cost of 1000 Luck, from the same
     *                                      fee-inclusive net cost
     */
    public record EctoScenarioDto(
            int ectoAcquisitionCostCopper,
            int dustGrossUnitPriceCopper,
            int expectedGrossRecoveredDustValueCopper,
            int netValueOfRecoveredDustCopper,
            int netCostPerEctoCopper,
            int profitPerEctoCopper,
            int costPer1000LuckCopper) {
    }
}
