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
    private final web.access.OwnerAccess ownerAccess;
    private final application.OwnerCatalogSummaryService catalogSummary;
    private final application.AccountOverviewService overview;

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

    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates,
                                     application.ScopedSourceGate gate) {
        this(globalDataRefreshService, priceCacheStatusService, access, accountStates, gate, null);
    }

    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates,
                                     application.ScopedSourceGate gate, web.access.OwnerAccess ownerAccess) {
        this(globalDataRefreshService, priceCacheStatusService, access, accountStates, gate, ownerAccess, null);
    }

    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates,
                                     application.ScopedSourceGate gate, web.access.OwnerAccess ownerAccess,
                                     application.OwnerCatalogSummaryService catalogSummary) {
        this(globalDataRefreshService, priceCacheStatusService, access, accountStates, gate, ownerAccess,
                catalogSummary, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SystemStatusApiController(GlobalDataRefreshService globalDataRefreshService,
                                     TradingPostPriceCacheStatusService priceCacheStatusService,
                                     AccountAccessService access, AccountStateReader accountStates,
                                     application.ScopedSourceGate gate, web.access.OwnerAccess ownerAccess,
                                     application.OwnerCatalogSummaryService catalogSummary,
                                     application.AccountOverviewService overview) {
        this.overview = overview;
        this.catalogSummary = catalogSummary;
        this.ownerAccess = ownerAccess;
        this.gate = gate;
        this.globalDataRefreshService = globalDataRefreshService;
        this.priceCacheStatusService = priceCacheStatusService;
        this.access = access;
        this.accountStates = accountStates;
    }

    /** Owner-only detailed shared diagnostics; authorization is decided before any status is read. */
    @GetMapping("/status/details")
    public ResponseEntity<?> details(HttpServletRequest request) {
        ResponseEntity<?> denial = ownerAccess == null
                ? denied(HttpStatus.FORBIDDEN, "OWNER_REQUIRED", "This operation is restricted to the system owner")
                : ownerAccess.deny(request);
        if (denial != null) return denial;
        // Aggregates are read only here, after authorization; the generic route never reaches them.
        var summary = catalogSummary == null ? null : catalogSummary.read();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new OwnerDetails(detailedStatus(), summary == null ? null : summary.items(),
                        summary == null ? null : summary.recipes(),
                        summary == null ? null : summary.tradingPostPrices(),
                        summary == null ? null : summary.accounts()));
    }

    /** Generic shared health plus the requesting account's own freshness; no detailed diagnostics. */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty() || access == null || accountStates == null) {
            return ResponseEntity.ok(genericStatus(null, null, null));
        }
        var resolution = access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        switch (resolution.outcome()) {
            case DELETION_INVALIDATED -> { return denied(HttpStatus.UNAUTHORIZED, "ACCOUNT_DATA_DELETED",
                    "This account's data was deleted; connect again to continue"); }
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
        Map<String, application.AccountOverviewService.SourceOverview> saved = null;
        try {
            AccountScope scope = AccountScope.of(UUID.fromString(context.accountId()));
            Map<String, AccountSourceStateRepository.SourceState> states = accountStates.read(scope, permitted);
            // Ordinary authorized use: activity only, never freshness, and only after the proof passed.
            if (gate != null) gate.recordUse(scope);
            if (overview != null) {
                // Only sources this live proof permits (grants and URL restrictions) reach the queries.
                Set<String> overviewSources = new java.util.LinkedHashSet<>(java.util.List.of(
                        AccountSyncStateRepository.CHARACTERS, AccountSyncStateRepository.RECIPES,
                        AccountSyncStateRepository.MATERIALS));
                overviewSources.retainAll(permitted);
                var read = overview.read(scope, overviewSources);
                saved = new AccountOverview(read.get(AccountSyncStateRepository.CHARACTERS),
                        read.get(AccountSyncStateRepository.RECIPES),
                        read.get(AccountSyncStateRepository.MATERIALS)).asMap();
            }
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
                .body(genericStatus(latest, scopeLabel, saved));
    }

    private static ResponseEntity<ApiErrorResponse> denied(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(code, message));
    }

    private GenericStatus genericStatus(Instant accountLastRefreshedAt, String accountRefreshScope,
                                        Map<String, application.AccountOverviewService.SourceOverview> saved) {
        DetailedStatus detail = detailedStatus();
        String global = detail.running() ? "RUNNING"
                : detail.lastFailure() != null ? "PROBLEM"
                : detail.lastCheckedAt() != null ? "OK" : "NOT_RUN";
        String prices = detail.priceCacheError() != null ? "PROBLEM"
                : detail.cachedPriceItems() != null && detail.cachedPriceItems() > 0 ? "OK" : "NOT_RUN";
        var o = saved == null || saved.isEmpty() ? null : new AccountOverview(saved.get("characters"),
                saved.get("recipes"), saved.get("materials"));
        return new GenericStatus(global, prices, accountLastRefreshedAt, accountRefreshScope, o);
    }

    private DetailedStatus detailedStatus() {
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
        return new DetailedStatus(global.running(), global.lastCheckedAt(), global.lastChangedAt(),
                global.lastRecipeSyncAt(), global.lastGraphRebuildAt(), global.lastFailure(),
                cachedPriceItems, stalePriceItems, newestPriceFetchedAt, priceCacheError);
    }

    /** Coarse states only ({@code OK|RUNNING|PROBLEM|NOT_RUN}); account fields appear only when authorized. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record GenericStatus(String globalHealth, String priceHealth,
                                Instant accountLastRefreshedAt, String accountRefreshScope,
                                AccountOverview accountOverview) { }

    /** Own-account saved-data counts; a member is absent when its source is not permitted. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AccountOverview(application.AccountOverviewService.SourceOverview characters,
                                  application.AccountOverviewService.SourceOverview recipes,
                                  application.AccountOverviewService.SourceOverview materials) {
        Map<String, application.AccountOverviewService.SourceOverview> asMap() {
            Map<String, application.AccountOverviewService.SourceOverview> m = new java.util.LinkedHashMap<>();
            if (characters != null) m.put("characters", characters);
            if (recipes != null) m.put("recipes", recipes);
            if (materials != null) m.put("materials", materials);
            return m;
        }
    }

    public record DetailedStatus(boolean running, Instant lastCheckedAt, Instant lastChangedAt,
                                 Instant lastRecipeSyncAt, Instant lastGraphRebuildAt,
                                 String lastFailure, Long cachedPriceItems,
                                 Long stalePriceItems, Instant newestPriceFetchedAt,
                                 String priceCacheError) { }

    /** Owner-only response: the existing diagnostics plus shared-data coverage and account counts. */
    public record OwnerDetails(@com.fasterxml.jackson.annotation.JsonUnwrapped DetailedStatus status,
                               application.OwnerCatalogSummaryService.Dataset items,
                               application.OwnerCatalogSummaryService.Dataset recipes,
                               application.OwnerCatalogSummaryService.Dataset tradingPostPrices,
                               application.OwnerCatalogSummaryService.Accounts accounts) { }
}
