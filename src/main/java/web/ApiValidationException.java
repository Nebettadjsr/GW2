package web;

/**
 * A request that cannot be turned into a valid calculation input (STORY-API-001). Thrown by
 * {@link CraftingProfitApiMapper} and {@link CraftingDiscoveryApiMapper} before any application
 * service is created or invoked, so an invalid request never starts a calculation;
 * {@link ApiExceptionHandler} maps it to HTTP 400.
 *
 * <p>Its message is composed from the request contract only, so it is safe to return to the
 * caller.
 */
public class ApiValidationException extends RuntimeException {

    public ApiValidationException(String message) {
        super(message);
    }
}
