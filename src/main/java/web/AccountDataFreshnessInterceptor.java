package web;

import application.AccountDataFreshnessPolicy;
import application.AccountRefreshCoordinator;
import org.springframework.web.servlet.HandlerInterceptor;
import repo.AccountSyncStateRepository;
import repo.Db;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Enforces the common persisted account-data freshness policy before account-dependent reads and
 * calculations that have not adopted their own cached-result contract.
 *
 * <p><b>Two deliberate limits (STORY-APP-014).</b>
 *
 * <ul>
 *   <li>Crafting Profit is no longer gated here. Under TARGET_ARCHITECTURE.md §9.1 that feature
 *       answers from its last successfully published inputs and reports their freshness in its own
 *       response, so refusing the request for expiry would contradict the contract. Every other
 *       route listed below keeps exactly the policy and the {@code ACCOUNT_DATA_STALE} response it
 *       had: this change enables one feature's contract, it does not quietly re-decide the
 *       others'.</li>
 *   <li>Refresh work is admitted through {@link AccountRefreshCoordinator} rather than by
 *       submitting under the shared operation key directly. The previous
 *       {@code submitOrExisting("ACCOUNT_SYNC", …)} handed the caller whichever account task
 *       happened to be unfinished, however narrow, so a selector-options request's
 *       {@code CHARACTERS}-only sync was advertised as the refresh of a caller's four stale
 *       sources and the following request was stale again (docs/bugs/BUG-2026-001). The
 *       coordinator accumulates each caller's required sources instead, keeps one account writer,
 *       and never reports an omitted source as refreshed.</li>
 * </ul>
 */
public final class AccountDataFreshnessInterceptor implements HandlerInterceptor {
    public static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(15);
    private final AccountRefreshCoordinator refreshCoordinator;
    private final Duration maxAge;

    public AccountDataFreshnessInterceptor(AccountRefreshCoordinator refreshCoordinator, Duration maxAge) {
        this.refreshCoordinator = refreshCoordinator;
        this.maxAge = maxAge;
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Set<String> required = sources(request.getRequestURI());
        if (required.isEmpty()) return true;
        Set<String> stale = new LinkedHashSet<>();
        try (var con = Db.open()) {
            AccountSyncStateRepository.ensure(con);
            var repo = new AccountSyncStateRepository();
            Instant now = Instant.now();
            for (String source : required) {
                var at = repo.lookup(con, source);
                if (AccountDataFreshnessPolicy.isStale(at, now, maxAge)) stale.add(source);
            }
        }
        if (stale.isEmpty()) return true;
        AccountRefreshCoordinator.Admission admission = refreshCoordinator.require(stale);
        throw new AccountDataStaleException(admission.taskId(), stale);
    }

    private static Set<String> sources(String path) {
        if (path.equals("/api/account/bank")) return Set.of(AccountSyncStateRepository.BANK);
        if (path.equals("/api/account/materials")) return Set.of(AccountSyncStateRepository.MATERIALS);
        if (path.equals("/api/account/luck")) return Set.of(AccountSyncStateRepository.LUCK);
        if (path.equals("/api/crafting/discovery") || path.equals("/api/crafting/discovery/resolution"))
            return Set.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
                    AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.CHARACTERS);
        if (path.equals("/api/crafting/selector-options")) return Set.of(AccountSyncStateRepository.CHARACTERS);
        return Set.of();
    }
}
