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
 * (STORY-API-001, STORY-API-002, STORY-API-008, TEST_STRATEGY.md §11).
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

    /**
     * The resolution-detail routes' only non-200 outcome that is not an error on the caller's or the
     * backend's side (STORY-API-008, TARGET_ARCHITECTURE.md §13.4): the request was valid and the
     * calculation ran, but its own fresh visible recipes do not include the requested one. Blocked
     * resolutions and unavailable calculation results are completed 200 responses, not failures, so
     * they never reach this advice.
     */
    @ExceptionHandler(RecipeNotInCalculationException.class)
    public ResponseEntity<ApiErrorResponse> handleRecipeNotInCalculation(RecipeNotInCalculationException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("RECIPE_NOT_IN_CALCULATION", e.getMessage()));
    }

    /** Body was absent-but-required, not JSON, or structurally unusable. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        LOG.log(Level.FINE, "Unreadable crafting request body", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("MALFORMED_REQUEST",
                        "Request body could not be read as JSON matching the crafting request contract"));
    }

    /** Authorized account, but its inputs were never published: a keyed sync must run first. */
    @ExceptionHandler(application.CraftingDiscoveryService.AccountInputsUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleInputsUnavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).header("Cache-Control", "no-store")
                .body(new ApiErrorResponse("CREDENTIAL_REQUIRED",
                        "Prove a GW2 API key and sync account data to use Discovery"));
    }

    /** A selected character outside the authorized account: the same answer as any scope mismatch. */
    @ExceptionHandler({application.CraftingDiscoveryService.CharacterNotOwnedException.class,
            application.CachedCraftingProfitService.CharacterNotOwnedException.class})
    public ResponseEntity<ApiErrorResponse> handleCharacterNotOwned() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header("Cache-Control", "no-store")
                .body(new ApiErrorResponse("ACCOUNT_ACCESS_DENIED",
                        "This session is not authorized for this account operation"));
    }

    @ExceptionHandler(ForeignOriginException.class)
    public ResponseEntity<ApiErrorResponse> handleForeignOrigin() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header("Cache-Control", "no-store")
                .body(new ApiErrorResponse("ORIGIN_REJECTED",
                        "This request did not come from this application's own origin"));
    }

    /** A keyed calculation whose proof or scope could not be established (API-010 semantics). */
    @ExceptionHandler(application.ScopedAccountRefreshCoordinator.Rejected.class)
    public ResponseEntity<ApiErrorResponse> handleRejectedProof(
            application.ScopedAccountRefreshCoordinator.Rejected rejected) {
        return switch (rejected.reason()) {
            case SESSION -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Cache-Control", "no-store")
                    .body(new ApiErrorResponse("SESSION_REQUIRED", "Prove a GW2 API key to continue"));
            case INVALID_CREDENTIAL, MALFORMED_PROOF -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("Cache-Control", "no-store")
                    .body(new ApiErrorResponse("INVALID_CREDENTIAL", "The GW2 API key could not be validated"));
            case ACCOUNT_MISMATCH, INSUFFICIENT_GRANTS -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header("Cache-Control", "no-store")
                    .body(new ApiErrorResponse("ACCOUNT_ACCESS_DENIED",
                            "This session is not authorized for this account operation"));
            case UPSTREAM_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Cache-Control", "no-store").header("Retry-After", "30")
                    .body(new ApiErrorResponse("PROOF_UNAVAILABLE",
                            "GW2 could not validate the key right now; retry shortly"));
        };
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
