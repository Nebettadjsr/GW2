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

    public AccountLuckApiController(AccountLuckService luckService) {
        this.luckService = luckService;
    }

    @GetMapping(path = "/luck", produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountLuckResponse luck(HttpServletRequest request)
            throws SQLException {
        if (!request.getParameterMap().isEmpty()) throw new ApiValidationException("Account Luck accepts no query parameters");
        AccountLuckService.AccountLuckStatus status = luckService.getStatus(RequestAccountContext.scopeOf(request))
                .orElseThrow(AccountLuckNotSyncedException::new);
        var progress = status.progress();
        List<AccountLuckResponse.TargetDto> targets = List.of(
                target("PLUS_5", status.targets().get(0)),
                target("PLUS_10", status.targets().get(1)),
                target("CAP", status.targets().get(2)));
        return new AccountLuckResponse(progress.consumedLuck(), progress.magicFindPercent(),
                progress.currentThreshold(),
                progress.nextThreshold() == null ? null : progress.magicFindPercent() + 1,
                progress.nextThreshold(), progress.remainingToNext(), progress.remainingToCap(),
                status.capThreshold(), status.fetchedAt(), targets);
    }

    private static AccountLuckResponse.TargetDto target(String kind, AccountLuckService.Target target) {
        return new AccountLuckResponse.TargetDto(kind, target.magicFindPercent(),
                target.cumulativeLuck(), target.remainingLuck());
    }
}
