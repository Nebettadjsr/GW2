package application;

import api.tp.EctoLivePriceGateway;
import craft.PriceQuote;
import ecto.EctoSalvageCalculator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-003) for
 * {@link EctoSalvageService}: a fake {@link EctoLivePriceGateway} replaces the live GW2 HTTP call,
 * so these run without HTTP, while the real {@link EctoSalvageCalculator} still performs the
 * actual fee/yield math - this suite proves quote handoff and orchestration, not domain math
 * (that is covered by {@code ecto.EctoSalvageCalculatorTest}).
 */
class EctoSalvageServiceTest {

    private static final int ECTO_INSTANT_BUY = 1000; // ecto.sellUnit
    private static final int ECTO_LISTING_BUY = 900;  // ecto.buyUnit
    private static final int DUST_INSTANT_SELL = 200; // dust.buyUnit
    private static final int DUST_LISTING_SELL = 240; // dust.sellUnit

    @Test
    void fetchesEctoAndDustQuotesAndEvaluatesAllFourCombinations() throws Exception {
        var gateway = new FakeGateway();
        gateway.canned = Map.of(
                EctoSalvageService.ECTO_ID, new PriceQuote(ECTO_LISTING_BUY, ECTO_INSTANT_BUY),
                EctoSalvageService.DUST_ID, new PriceQuote(DUST_INSTANT_SELL, DUST_LISTING_SELL));
        var service = new EctoSalvageService(gateway);

        EctoSalvageService.EctoScenarios scenarios = service.calculate();

        assertArrayEquals(new int[]{EctoSalvageService.ECTO_ID, EctoSalvageService.DUST_ID}, gateway.capturedItemIds);
        assertTrue(scenarios.available());

        assertEquals(EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_INSTANT_SELL), scenarios.instantBuyInstantSell());
        assertEquals(EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_LISTING_SELL), scenarios.instantBuyListingSell());
        assertEquals(EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_INSTANT_SELL), scenarios.listingBuyInstantSell());
        assertEquals(EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_LISTING_SELL), scenarios.listingBuyListingSell());
    }

    @Test
    void missingEctoQuoteReturnsUnavailableScenariosWithoutEvaluating() throws Exception {
        var gateway = new FakeGateway();
        gateway.canned = Map.of(
                EctoSalvageService.DUST_ID, new PriceQuote(DUST_INSTANT_SELL, DUST_LISTING_SELL));
        var service = new EctoSalvageService(gateway);

        EctoSalvageService.EctoScenarios scenarios = service.calculate();

        assertFalse(scenarios.available());
        assertSame(EctoSalvageService.EctoScenarios.UNAVAILABLE, scenarios);
        assertNull(scenarios.instantBuyInstantSell());
        assertNull(scenarios.instantBuyListingSell());
        assertNull(scenarios.listingBuyInstantSell());
        assertNull(scenarios.listingBuyListingSell());
    }

    @Test
    void missingDustQuoteReturnsUnavailableScenarios() throws Exception {
        var gateway = new FakeGateway();
        gateway.canned = Map.of(
                EctoSalvageService.ECTO_ID, new PriceQuote(ECTO_LISTING_BUY, ECTO_INSTANT_BUY));
        var service = new EctoSalvageService(gateway);

        assertFalse(service.calculate().available());
    }

    @Test
    void gatewayFailurePropagatesFromCalculate() {
        var gateway = new FakeGateway();
        gateway.failure = new IOException("simulated TP fetch failure");
        var service = new EctoSalvageService(gateway);

        Exception thrown = assertThrows(IOException.class, service::calculate);
        assertSame(gateway.failure, thrown);
    }

    private static class FakeGateway extends EctoLivePriceGateway {
        Map<Integer, PriceQuote> canned = new HashMap<>();
        Exception failure;
        int[] capturedItemIds;

        @Override
        public Map<Integer, PriceQuote> fetchQuotes(int... itemIds) throws Exception {
            capturedItemIds = itemIds;
            if (failure != null) throw failure;
            return canned;
        }
    }
}
