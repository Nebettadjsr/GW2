package web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps failures of the crafting calculation controllers onto the HTTP status contract
 * (STORY-API-001, STORY-API-002, TEST_STRATEGY.md §11).
 *
 * <p>Scoped to those controllers with {@code assignableTypes} on purpose: a blanket
 * {@code RuntimeException} handler applied application-wide would also swallow the framework's own
 * routing exceptions and turn an unknown path into a 500 instead of a 404. Both routes share one
 * advice because they share the same failure modes - the same validation exception, the same
 * {@code SQLException} out of the same repositories, the same crafting-graph cache failure - and a
 * caller should not have to branch on which route it called to interpret an error code.
 *
 * <p>Only validation messages - which this application composes from the request contract - are
 * returned to the caller. Infrastructure and calculation failures are logged in full server-side
 * and answered with a fixed message, so connection details and stack traces do not leave the
 * backend.
 */
@RestControllerAdvice(assignableTypes = {
        CraftingProfitApiController.class,
        CraftingDiscoveryApiController.class})
public class ApiExceptionHandler {

    private static final Logger LOG = Logger.getLogger(ApiExceptionHandler.class.getName());

    /** Request could not describe a calculation; no calculation was started. */
    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ApiValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    /** Body was absent-but-required, not JSON, or structurally unusable. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        LOG.log(Level.FINE, "Unreadable crafting request body", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("MALFORMED_REQUEST",
                        "Request body could not be read as JSON matching the crafting request contract"));
    }

    /** The database backing the calculation could not be read. */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> handleSqlFailure(SQLException e) {
        LOG.log(Level.SEVERE, "Crafting calculation failed reading the database", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("DATA_STORE_UNAVAILABLE",
                        "The crafting data store is currently unavailable"));
    }

    /**
     * Any other failure raised while the use case ran - including the crafting-graph cache load
     * failure both application services wrap in a {@code RuntimeException}.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleCalculationFailure(RuntimeException e) {
        LOG.log(Level.SEVERE, "Crafting calculation failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("CALCULATION_FAILED",
                        "The crafting calculation could not be completed"));
    }
}
