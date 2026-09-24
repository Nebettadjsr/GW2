package application;

import org.junit.jupiter.api.Test;
import repo.AppConfig;
import sync.AccountRefreshGateway;
import sync.GlobalDataRefreshGateway;
import sync.IconSyncGateway;
import sync.TradingPostPriceRefreshGateway;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-007) for
 * {@link InitialSetupService}: fake {@link AccountRefreshGateway}, {@link GlobalDataRefreshGateway},
 * {@link TradingPostPriceRefreshGateway} and {@link IconSyncGateway} collaborators replace the
 * live GW2 API/database calls, so these run without live HTTP or a database (the icon step's
 * {@code repo.AppConfig.ICON_CACHE_DIR} read is a local environment/.env lookup, unchanged from
 * the pre-extraction {@code InitialSetupService.firstFill()}, not a live call). Proves the exact
 * pre-extraction call order (account bank/materials/recipes, global recipes, TP tradeable items,
 * discovery price refresh, profit price refresh, icon URLs, icon disk download), the icon step's
 * forwarded path, and that a failure from any step propagates immediately and short-circuits the
 * remaining steps - the same behavior the removed top-level {@code InitialSetupService} had.
 */
class InitialSetupServiceTest {

    @Test
    void firstFillInvokesEachStepInTheExistingOrder() throws Exception {
        var calls = new ArrayList<String>();
        var accountGateway = new RecordingAccountGateway(calls);
        var globalDataGateway = new RecordingGlobalDataGateway(calls);
        var priceGateway = new RecordingPriceGateway(calls);
        var iconGateway = new RecordingIconGateway(calls);
        var service = new InitialSetupService(accountGateway, globalDataGateway,
                new TradingPostPriceRefreshService(priceGateway), iconGateway);

        service.firstFill();

        assertEquals(
                List.of("bank", "materials", "recipes", "globalRecipes", "tradeableItems",
                         "discoveryPrices", "profitPrices", "iconUrls", "iconsToDisk"),
                calls);
        assertEquals(Path.of(AppConfig.ICON_CACHE_DIR), iconGateway.receivedIconBaseDir);
    }

    @Test
    void bankFailureShortCircuitsEveryLaterStep() {
        var calls = new ArrayList<String>();
        var accountGateway = new RecordingAccountGateway(calls);
        accountGateway.bankFailure = new IOException("simulated bank fetch failure");
        var service = new InitialSetupService(accountGateway, new RecordingGlobalDataGateway(calls),
                new TradingPostPriceRefreshService(new RecordingPriceGateway(calls)),
                new RecordingIconGateway(calls));

        Exception thrown = assertThrows(IOException.class, service::firstFill);

        assertSame(accountGateway.bankFailure, thrown);
        assertEquals(List.of(), calls);
    }

    @Test
    void globalRecipesFailureShortCircuitsTradeableItemsPricesAndIconsButAccountStepsAlreadyRan() {
        var calls = new ArrayList<String>();
        var globalDataGateway = new RecordingGlobalDataGateway(calls);
        globalDataGateway.globalRecipesFailure = new RuntimeException("simulated global recipe sync failure");
        var service = new InitialSetupService(new RecordingAccountGateway(calls), globalDataGateway,
                new TradingPostPriceRefreshService(new RecordingPriceGateway(calls)),
                new RecordingIconGateway(calls));

        Exception thrown = assertThrows(RuntimeException.class, service::firstFill);

        assertSame(globalDataGateway.globalRecipesFailure, thrown);
        assertEquals(List.of("bank", "materials", "recipes"), calls);
    }

    @Test
    void discoveryPriceFailureShortCircuitsProfitPricesAndIconsButAccountAndGlobalStepsAlreadyRan() {
        var calls = new ArrayList<String>();
        var priceGateway = new RecordingPriceGateway(calls);
        priceGateway.discoveryFailure = new IOException("simulated discovery price fetch failure");
        var service = new InitialSetupService(new RecordingAccountGateway(calls), new RecordingGlobalDataGateway(calls),
                new TradingPostPriceRefreshService(priceGateway), new RecordingIconGateway(calls));

        Exception thrown = assertThrows(IOException.class, service::firstFill);

        assertSame(priceGateway.discoveryFailure, thrown);
        assertEquals(List.of("bank", "materials", "recipes", "globalRecipes", "tradeableItems"), calls);
    }

    @Test
    void iconUrlsFailurePropagatesAfterEveryEarlierStepRanAndShortCircuitsIconsToDisk() {
        var calls = new ArrayList<String>();
        var iconGateway = new RecordingIconGateway(calls);
        iconGateway.iconUrlsFailure = new RuntimeException("simulated icon URL sync failure");
        var service = new InitialSetupService(new RecordingAccountGateway(calls), new RecordingGlobalDataGateway(calls),
                new TradingPostPriceRefreshService(new RecordingPriceGateway(calls)), iconGateway);

        Exception thrown = assertThrows(RuntimeException.class, service::firstFill);

        assertSame(iconGateway.iconUrlsFailure, thrown);
        assertEquals(
                List.of("bank", "materials", "recipes", "globalRecipes", "tradeableItems",
                         "discoveryPrices", "profitPrices"),
                calls);
    }

    private static class RecordingAccountGateway extends AccountRefreshGateway {
        final List<String> calls;
        Exception bankFailure;
        Exception materialsFailure;
        Exception recipesFailure;

        RecordingAccountGateway(List<String> calls) { this.calls = calls; }

        @Override
        public void syncAccountBank() throws Exception {
            if (bankFailure != null) throw bankFailure;
            calls.add("bank");
        }

        @Override
        public void syncAccountMaterials() throws Exception {
            if (materialsFailure != null) throw materialsFailure;
            calls.add("materials");
        }

        @Override
        public void syncAccountRecipes() throws Exception {
            if (recipesFailure != null) throw recipesFailure;
            calls.add("recipes");
        }
    }

    private static class RecordingGlobalDataGateway extends GlobalDataRefreshGateway {
        final List<String> calls;
        Exception globalRecipesFailure;
        Exception tradeableItemsFailure;

        RecordingGlobalDataGateway(List<String> calls) { this.calls = calls; }

        @Override
        public void syncAllRecipesGlobalSafe() throws Exception {
            if (globalRecipesFailure != null) throw globalRecipesFailure;
            calls.add("globalRecipes");
        }

        @Override
        public void syncTpTradeableItems() throws Exception {
            if (tradeableItemsFailure != null) throw tradeableItemsFailure;
            calls.add("tradeableItems");
        }
    }

    private static class RecordingPriceGateway extends TradingPostPriceRefreshGateway {
        final List<String> calls;
        Exception discoveryFailure;
        Exception profitFailure;

        RecordingPriceGateway(List<String> calls) { this.calls = calls; }

        @Override
        public void syncTpPricesForDiscovery() throws Exception {
            if (discoveryFailure != null) throw discoveryFailure;
            calls.add("discoveryPrices");
        }

        @Override
        public void syncTpPricesForProfit() throws Exception {
            if (profitFailure != null) throw profitFailure;
            calls.add("profitPrices");
        }
    }

    private static class RecordingIconGateway extends IconSyncGateway {
        final List<String> calls;
        Exception iconUrlsFailure;
        Exception iconsToDiskFailure;
        Path receivedIconBaseDir;

        RecordingIconGateway(List<String> calls) { this.calls = calls; }

        @Override
        public void syncItemIconUrls() throws Exception {
            if (iconUrlsFailure != null) throw iconUrlsFailure;
            calls.add("iconUrls");
        }

        @Override
        public void syncItemIconsToDisk(Path iconBaseDir) throws Exception {
            receivedIconBaseDir = iconBaseDir;
            if (iconsToDiskFailure != null) throw iconsToDiskFailure;
            calls.add("iconsToDisk");
        }
    }
}
