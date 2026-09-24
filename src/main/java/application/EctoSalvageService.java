package application;

import api.tp.EctoLivePriceGateway;
import craft.PriceQuote;
import ecto.EctoSalvageCalculator;

import java.util.Map;

/**
 * Application-layer use case for the Ectoplasm Salvage flow (TARGET_ARCHITECTURE.md §8,
 * STORY-APP-003): fetches live Trading Post quotes for Ecto/Dust via {@link EctoLivePriceGateway}
 * and evaluates the single {@link EctoSalvageCalculator} across all four Ecto-buy/Dust-sell
 * combinations. Holds no JavaFX dependency and reimplements no domain calculation; {@code
 * EctoView} is its sole caller.
 */
public class EctoSalvageService {

    public static final int ECTO_ID = 19721;
    public static final int DUST_ID = 24277;

    private final EctoLivePriceGateway priceGateway;

    public EctoSalvageService() {
        this(new EctoLivePriceGateway());
    }

    /** Seam used by application-layer tests to substitute a fake price gateway (TARGET_ARCHITECTURE.md §25). */
    public EctoSalvageService(EctoLivePriceGateway priceGateway) {
        this.priceGateway = priceGateway;
    }

    /**
     * All four Ecto-buy/Dust-sell scenario results for one price snapshot. Every field is {@code
     * null} when the Trading Post did not return usable quotes for both Ecto and Dust (mirrors the
     * pre-extraction {@code EctoView} "missing data" branch, distinct from a thrown fetch failure).
     */
    public record EctoScenarios(
            EctoSalvageCalculator.ScenarioResult instantBuyInstantSell,
            EctoSalvageCalculator.ScenarioResult instantBuyListingSell,
            EctoSalvageCalculator.ScenarioResult listingBuyInstantSell,
            EctoSalvageCalculator.ScenarioResult listingBuyListingSell) {

        public static final EctoScenarios UNAVAILABLE = new EctoScenarios(null, null, null, null);

        public boolean available() {
            return instantBuyInstantSell != null;
        }
    }

    /**
     * Meanings (matches the pre-extraction {@code EctoView} wording): Instant Buy = sells.unit_price,
     * Listing Buy = buys.unit_price, Instant Sell = buys.unit_price, Listing Sell = sells.unit_price.
     */
    public EctoScenarios calculate() throws Exception {
        Map<Integer, PriceQuote> quotes = priceGateway.fetchQuotes(ECTO_ID, DUST_ID);
        PriceQuote ecto = quotes.get(ECTO_ID);
        PriceQuote dust = quotes.get(DUST_ID);

        if (ecto == null || dust == null) {
            return EctoScenarios.UNAVAILABLE;
        }

        int ectoInstantBuy = ecto.sellUnit;
        int ectoListingBuy = ecto.buyUnit;
        int dustInstantSell = dust.buyUnit;
        int dustListingSell = dust.sellUnit;

        return new EctoScenarios(
                EctoSalvageCalculator.evaluate(ectoInstantBuy, dustInstantSell),
                EctoSalvageCalculator.evaluate(ectoInstantBuy, dustListingSell),
                EctoSalvageCalculator.evaluate(ectoListingBuy, dustInstantSell),
                EctoSalvageCalculator.evaluate(ectoListingBuy, dustListingSell));
    }
}
