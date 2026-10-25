package web;

import application.AccountLuckService;
import application.CurrentAccountLuckService.AccountLuckNotSyncedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.access.RequestAccountContext;
import web.dto.AccountLuckResponse;

import java.sql.SQLException;
import java.util.List;

/**
 * Read of the authorized session's stored Luck. No account ID or GW2 key is accepted from the browser,
 * and no configured credential is used: a missing row is reported, never fetched.
 */
@RestController
@RequestMapping("/api/account")
public class AccountLuckApiController {
    private final AccountLuckService luckService;

    private final application.ScopedSourceGate gate;

    public AccountLuckApiController(AccountLuckService luckService) {
        this(luckService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountLuckApiController(AccountLuckService luckService, application.ScopedSourceGate gate) {
        this.luckService = luckService;
        this.gate = gate;
    }

    @GetMapping(path = "/luck", produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountLuckResponse luck(HttpServletRequest request)
            throws SQLException {
        if (!request.getParameterMap().isEmpty()) throw new ApiValidationException("Account Luck accepts no query parameters");
        var scope = RequestAccountContext.scopeOf(request);
        var read = application.ScopedSourceGate.through(gate, scope, repo.AccountSyncStateRepository.LUCK,
                () -> luckService.getStatus(scope));
        // Never published (or no stored row): the existing not-synced answer, never zero Luck.
        if (!read.hasData() || read.data().isEmpty()) throw new AccountLuckNotSyncedException();
        AccountLuckService.AccountLuckStatus status = read.data().get();
        var freshness = gate == null ? null : web.dto.AccountSourceFreshnessDto.of(read);
        var progress = status.progress();
        List<AccountLuckResponse.TargetDto> targets = List.of(
                target("PLUS_5", status.targets().get(0)),
                target("PLUS_10", status.targets().get(1)),
                target("CAP", status.targets().get(2)));
        return new AccountLuckResponse(progress.consumedLuck(), progress.magicFindPercent(),
                progress.currentThreshold(),
                progress.nextThreshold() == null ? null : progress.magicFindPercent() + 1,
                progress.nextThreshold(), progress.remainingToNext(), progress.remainingToCap(),
                status.capThreshold(), status.fetchedAt(), targets, freshness);
    }

    private static AccountLuckResponse.TargetDto target(String kind, AccountLuckService.Target target) {
        return new AccountLuckResponse.TargetDto(kind, target.magicFindPercent(),
                target.cumulativeLuck(), target.remainingLuck());
    }
}
