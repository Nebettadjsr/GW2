package web;

import application.GlobalDataRefreshService;
import application.AccountRefreshService;
import application.TradingPostPriceCacheStatusService;
import java.time.Instant;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight, process-local diagnostics for global metadata refresh. */
@RestController
@RequestMapping("/api/system")
public class SystemStatusApiController {
    private final GlobalDataRefreshService globalDataRefreshService;
    private final AccountRefreshService accountRefreshService;
    private final TradingPostPriceCacheStatusService priceCacheStatusService;

    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     AccountRefreshService accountRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService) {
        this.globalDataRefreshService = globalDataRefreshService;
        this.accountRefreshService = accountRefreshService;
        this.priceCacheStatusService = priceCacheStatusService;
    }

    @GetMapping("/status")
    public SystemStatus status() {
        var global = globalDataRefreshService.status();
        Long cachedPriceItems = null;
        Long stalePriceItems = null;
        Instant newestPriceFetchedAt = null;
        String priceCacheError = null;
        try {
            var prices = priceCacheStatusService.read();
            cachedPriceItems = prices.cachedItems();
            stalePriceItems = prices.staleItems();
            newestPriceFetchedAt = prices.newestFetchedAt() == null ? null : prices.newestFetchedAt().toInstant();
        } catch (SQLException e) {
            priceCacheError = "Database status unavailable";
        }
        return new SystemStatus(global.running(), global.lastCheckedAt(), global.lastChangedAt(),
                global.lastRecipeSyncAt(), global.lastGraphRebuildAt(), global.lastFailure(),
                accountRefreshService.lastRefreshedAt(), accountRefreshService.lastRefreshScope(),
                cachedPriceItems, stalePriceItems, newestPriceFetchedAt, priceCacheError);
    }

    public record SystemStatus(boolean running, Instant lastCheckedAt, Instant lastChangedAt,
                                Instant lastRecipeSyncAt, Instant lastGraphRebuildAt,
                                String lastFailure, Instant accountLastRefreshedAt,
                                String accountRefreshScope, Long cachedPriceItems,
                                Long stalePriceItems, Instant newestPriceFetchedAt,
                                String priceCacheError) { }
}
