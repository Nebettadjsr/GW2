package web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

import java.sql.SQLException;

@RestControllerAdvice(assignableTypes = AccountLuckApiController.class)
public class AccountLuckApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AccountLuckApiExceptionHandler.class);
    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> invalid(ApiValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(AccountLuckNotSyncedException.class)
    public ResponseEntity<ApiErrorResponse> notSynced() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                "CREDENTIAL_REQUIRED", "Prove a GW2 API key and sync account data to load consumed Luck"));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> databaseUnavailable(SQLException error) {
        LOG.error("Account Luck database read failed", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiErrorResponse(
                "DATA_STORE_UNAVAILABLE", "The account data store is currently unavailable"));
    }
}
