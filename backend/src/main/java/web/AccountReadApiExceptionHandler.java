package web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Status mapping for the two account read routes (STORY-API-007): {@code GET /api/account/bank} and
 * {@code GET /api/account/materials}.
 *
 * <p>One advice for both, rather than one each, because the two routes fail the same way for the
 * same reasons and a caller already knows which of them it called; a per-route error code would add
 * nothing it cannot see. It stays separate from {@link ApiExceptionHandler} for the reason
 * {@link CraftingSelectorOptionsApiExceptionHandler} records: that advice's 500
 * {@code CALCULATION_FAILED} describes a crafting calculation, which is the wrong thing to tell a
 * caller about an inventory read. The code that means the same thing keeps the same name - a
 * {@link SQLException} is the same 503 {@code DATA_STORE_UNAVAILABLE} here as at the other read
 * boundaries - so a caller's error handling still works across all of them. Scoped with
 * {@code assignableTypes}, so it does not intercept the framework's own 404/405 routing responses.
 *
 * <p>There is no 400 path: both routes take no body and no parameter, so no request can be invalid.
 *
 * <p>Only fixed messages are returned. Failure detail is logged server-side, so connection details,
 * SQL and stack traces do not leave the backend.
 */
@RestControllerAdvice(assignableTypes = {
        BankContentsApiController.class,
        MaterialStorageApiController.class})
public class AccountReadApiExceptionHandler {

    private static final Logger LOG = Logger.getLogger(AccountReadApiExceptionHandler.class.getName());

    /** The database backing the account read could not be read. */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> handleSqlFailure(SQLException e) {
        LOG.log(Level.SEVERE, "Account read failed reading the database", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("DATA_STORE_UNAVAILABLE",
                        "The crafting data store is currently unavailable"));
    }

    /** Any other failure raised while the account read ran. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleReadFailure(RuntimeException e) {
        LOG.log(Level.SEVERE, "Account read could not be completed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("ACCOUNT_READ_FAILED",
                        "The account inventory could not be read"));
    }
}
