package web;

import application.AccountRefreshService;
import application.AccountDataFreshnessPolicy;
import org.springframework.web.servlet.HandlerInterceptor;
import repo.AccountSyncStateRepository;
import repo.Db;
import web.task.BackgroundTaskService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Enforces the common persisted account-data freshness policy before account-dependent reads/calculations. */
public final class AccountDataFreshnessInterceptor implements HandlerInterceptor {
    public static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(15);
    private final AccountRefreshService refresh;
    private final BackgroundTaskService tasks;
    private final Duration maxAge;

    public AccountDataFreshnessInterceptor(AccountRefreshService refresh, BackgroundTaskService tasks, Duration maxAge) {
        this.refresh = refresh; this.tasks = tasks; this.maxAge = maxAge;
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
                var at = repo.find(con, source);
                if (AccountDataFreshnessPolicy.isStale(at, now, maxAge)) stale.add(source);
            }
        }
        if (stale.isEmpty()) return true;
        String taskId = tasks.submitOrExisting("ACCOUNT_SYNC", () -> refresh.refreshSources(stale));
        throw new AccountDataStaleException(taskId, stale);
    }

    private static Set<String> sources(String path) {
        if (path.equals("/api/account/bank")) return Set.of(AccountSyncStateRepository.BANK);
        if (path.equals("/api/account/materials")) return Set.of(AccountSyncStateRepository.MATERIALS);
        if (path.equals("/api/account/luck")) return Set.of(AccountSyncStateRepository.LUCK);
        if (path.equals("/api/crafting/profit") || path.equals("/api/crafting/profit/resolution")
                || path.equals("/api/crafting/discovery") || path.equals("/api/crafting/discovery/resolution"))
            return Set.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
                    AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.CHARACTERS);
        if (path.equals("/api/crafting/selector-options")) return Set.of(AccountSyncStateRepository.CHARACTERS);
        return Set.of();
    }
}
