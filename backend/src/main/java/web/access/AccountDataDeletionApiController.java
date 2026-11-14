package web.access;

import application.AccountDataDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.ApiErrorResponse;

/**
 * {@code DELETE /api/account/data} (STORY-DELETE-001): deletes the cached data of the account the
 * live session was verified for. No body, no path or query identity, no key: the target comes only
 * from the context {@link AccountAccessInterceptor} resolved. Every outcome is {@code no-store}.
 */
@RestController
public class AccountDataDeletionApiController {

    private final AccountDataDeletionService deletion;
    private final AccountAccessHttp http;

    public AccountDataDeletionApiController(AccountDataDeletionService deletion, AccountAccessHttp http) {
        this.deletion = deletion;
        this.http = http;
    }

    @DeleteMapping("/api/account/data")
    public ResponseEntity<?> delete(HttpServletRequest request) {
        if (!http.isSameOrigin(request)) {
            return error(HttpStatus.FORBIDDEN, "ORIGIN_REJECTED",
                    "This request did not come from this application's own origin");
        }
        var scope = RequestAccountContext.scopeOf(request);
        return switch (deletion.delete(scope, 0)) {
            case DELETED -> ResponseEntity.status(HttpStatus.NO_CONTENT)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
            case WORK_IN_PROGRESS -> error(HttpStatus.CONFLICT, "ACCOUNT_WORK_IN_PROGRESS",
                    "Account data is being refreshed; try again when it has finished");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "DATA_STORE_UNAVAILABLE",
                    "The account data could not be deleted right now");
        };
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(code, message));
    }
}
