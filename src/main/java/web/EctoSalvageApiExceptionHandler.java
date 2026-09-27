package web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Status mapping for the Ectoplasm Salvage route (STORY-WEB-013).
 *
 * <p>A separate advice from {@link ApiExceptionHandler}, for the reason
 * {@link CraftingSelectorOptionsApiExceptionHandler} already records: that one's 500
 * {@code CALCULATION_FAILED} describes a crafting calculation over the synchronized database, which
 * is the wrong thing to tell a caller whose Ectoplasm request failed at the live Trading Post
 * lookup. Scoped with {@code assignableTypes}, so it does not intercept the framework's own 404/405
 * routing responses.
 *
 * <p>{@code application.EctoSalvageService.calculate()} declares {@code Exception}, so the failure
 * handler here catches {@code Exception} rather than {@code RuntimeException}: the checked
 * {@code IOException} of a failed live fetch is the expected case, not the exceptional one.
 *
 * <p>Only the validation message - which this application composes from the route contract - is
 * returned to the caller. Failure detail is logged server-side, so upstream URLs, HTTP status text
 * and stack traces do not leave the backend.
 */
@RestControllerAdvice(assignableTypes = EctoSalvageApiController.class)
public class EctoSalvageApiExceptionHandler {

    private static final Logger LOG = Logger.getLogger(EctoSalvageApiExceptionHandler.class.getName());

    /** The request tried to parameterise a use case that takes no input; nothing was calculated. */
    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ApiValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("UNSUPPORTED_REQUEST", e.getMessage()));
    }

    /**
     * The live price lookup or the calculation behind it failed.
     *
     * <p>502 rather than 500: this route's own work is a call to an upstream service the backend
     * does not control, and a caller's sensible response is to try again rather than to treat the
     * backend as broken.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleCalculationFailure(Exception e) {
        LOG.log(Level.SEVERE, "Ectoplasm salvage calculation failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("PRICE_SOURCE_UNAVAILABLE",
                        "Live Trading Post prices for the Ectoplasm calculation are currently unavailable"));
    }
}
