package web;

import application.access.AccountLifecycleGate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

/** A lifecycle refusal is retryable, including when proof completed during another request's deletion. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountLifecycleApiExceptionHandler {
    // This exact application exception must precede controller-specific generic RuntimeException handlers.
    @ExceptionHandler(AccountLifecycleGate.Unavailable.class)
    public ResponseEntity<ApiErrorResponse> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse("DATA_STORE_UNAVAILABLE", "The account is busy; try again shortly"));
    }
}
