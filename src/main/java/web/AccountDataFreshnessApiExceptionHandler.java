package web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

@RestControllerAdvice(assignableTypes = {BankContentsApiController.class, MaterialStorageApiController.class,
        AccountLuckApiController.class, CraftingProfitApiController.class, CraftingDiscoveryApiController.class,
        CraftingSelectorOptionsApiController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountDataFreshnessApiExceptionHandler {
    @ExceptionHandler(AccountDataStaleException.class)
    public ResponseEntity<ApiErrorResponse> stale(AccountDataStaleException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiErrorResponse(
                "ACCOUNT_DATA_STALE", "Account data is being refreshed",
                SyncTaskApiController.TASKS_PATH + "/" + e.taskId(), e.sources()));
    }
}
