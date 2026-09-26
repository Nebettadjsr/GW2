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
 * Status mapping for the crafting selector-options route (STORY-API-006).
 *
 * <p>A separate advice from {@link ApiExceptionHandler} for the reason
 * {@link SyncApiExceptionHandler} already records: that one's 500 {@code CALCULATION_FAILED}
 * describes a crafting calculation, which is the wrong thing to tell a caller about a selector
 * read, and the calculation routes' established contract must not be reworded to make room for this
 * one. The code that means the same thing keeps the same name - a {@link SQLException} is the same
 * 503 {@code DATA_STORE_UNAVAILABLE} here as there - so a caller's error handling still works across
 * both boundaries. Scoped with {@code assignableTypes}, so it does not intercept the framework's own
 * 404/405 routing responses.
 *
 * <p>There is no 400 path: the route takes no body and no parameter, so no request can be invalid.
 *
 * <p>Only fixed messages are returned. Failure detail is logged server-side, so connection details,
 * SQL and stack traces do not leave the backend.
 */
@RestControllerAdvice(assignableTypes = CraftingSelectorOptionsApiController.class)
public class CraftingSelectorOptionsApiExceptionHandler {

    private static final Logger LOG = Logger.getLogger(CraftingSelectorOptionsApiExceptionHandler.class.getName());

    /** The database backing the selector read could not be read. */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> handleSqlFailure(SQLException e) {
        LOG.log(Level.SEVERE, "Crafting selector options failed reading the database", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("DATA_STORE_UNAVAILABLE",
                        "The crafting data store is currently unavailable"));
    }

    /** Any other failure raised while the selector read ran. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleReadFailure(RuntimeException e) {
        LOG.log(Level.SEVERE, "Crafting selector options could not be read", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("SELECTOR_OPTIONS_FAILED",
                        "The crafting selector options could not be read"));
    }
}
