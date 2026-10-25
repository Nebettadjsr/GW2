package web;

import application.GlobalDataRefreshService;
import application.TradingPostPriceCacheStatusService;
import application.access.AccountAccessService;
import application.access.AccountContext;
import application.access.AccountOperation;
import jakarta.servlet.http.HttpServletRequest;
import model.AccountScope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import web.access.AccountAccessHttp;
import web.dto.ApiErrorResponse;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight diagnostics. The execution and price-cache facts are global and need no account. The
 * two {@code account*} members are the requesting account's own freshness projection (STORY-API-011):
 * they are filled only for a live session whose access context matches, limited to the sources its
 * grants permit, and are otherwise absent. A presented session that fails that proof is answered
 * 401/403 exactly like the account routes, so a bad proof never silently degrades into a response.
 */
@RestController
@RequestMapping("/api/system")
public class SystemStatusApiController {
    private final GlobalDataRefreshService globalDataRefreshService;
    private final TradingPostPriceCacheStatusService priceCacheStatusService;

    private final AccountAccessService access;
    private final AccountStateReader accountStates;
    private final application.ScopedSourceGate gate;

    /** Reads one account's published source markers; a seam so the projection is testable offline. */
    @FunctionalInterface
    public interface AccountStateReader {
        Map<String, AccountSourceStateRepository.SourceState> read(AccountScope scope, Set<String> sources)
                throws SQLException;
    }

    /** Global diagnostics only: with no account facility no account projection is possible. */
    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService) {
        this(globalDataRefreshService, priceCacheStatusService, null, null, null);
    }

    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates) {
        this(globalDataRefreshService, priceCacheStatusService, access, accountStates, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates,
                                     application.ScopedSourceGate gate) {
        this.gate = gate;
        this.globalDataRefreshService = globalDataRefreshService;
        this.priceCacheStatusService = priceCacheStatusService;
        this.access = access;
        this.accountStates = accountStates;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty() || access == null || accountStates == null) {
            return ResponseEntity.ok(globalStatus(null, null));
        }
        var resolution = access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> { return denied(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED",
                    "Prove a GW2 API key to continue"); }
            case CONTEXT_MISMATCH -> { return denied(HttpStatus.FORBIDDEN, "ACCOUNT_ACCESS_DENIED",
                    "This session is not authorized for this account operation"); }
            case AUTHENTICATED -> { /* project below */ }
        }
        AccountContext context = resolution.context();
        Set<String> permitted = new java.util.LinkedHashSet<>();
        for (String source : java.util.List.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
                AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.LUCK,
                AccountSyncStateRepository.CHARACTERS)) {
            AccountOperation operation = application.ScopedAccountRefreshCoordinator.operationFor(source);
            if (operation != null && context.permits(operation)) permitted.add(source);
        }
        Instant latest = null;
        String scopeLabel = null;
        try {
            AccountScope scope = AccountScope.of(UUID.fromString(context.accountId()));
            Map<String, AccountSourceStateRepository.SourceState> states = accountStates.read(scope, permitted);
            // Ordinary authorized use: activity only, never freshness, and only after the proof passed.
            if (gate != null) gate.recordUse(scope);
            Map<String, AccountSourceStateRepository.SourceState> sorted = new TreeMap<>(states);
            for (var state : sorted.values()) {
                if (latest == null || state.completedAt().isAfter(latest)) latest = state.completedAt();
            }
            if (!sorted.isEmpty()) scopeLabel = String.join(",", sorted.keySet());
        } catch (SQLException e) {
            // The account projection is unavailable; the global facts below are unaffected.
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext())
                .body(globalStatus(latest, scopeLabel));
    }

    private static ResponseEntity<ApiErrorResponse> denied(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(code, message));
    }

    private SystemStatus globalStatus(Instant accountLastRefreshedAt, String accountRefreshScope) {
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
                accountLastRefreshedAt, accountRefreshScope,
                cachedPriceItems, stalePriceItems, newestPriceFetchedAt, priceCacheError);
    }

    public record SystemStatus(boolean running, Instant lastCheckedAt, Instant lastChangedAt,
                                Instant lastRecipeSyncAt, Instant lastGraphRebuildAt,
                                String lastFailure, Instant accountLastRefreshedAt,
                                String accountRefreshScope, Long cachedPriceItems,
                                Long stalePriceItems, Instant newestPriceFetchedAt,
                                String priceCacheError) { }
}
