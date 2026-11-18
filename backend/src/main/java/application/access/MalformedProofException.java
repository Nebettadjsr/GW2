package application.access;

/**
 * An upstream validation response was not the documented shape, so nothing about the key was
 * established. Validation fails closed: no session is issued and no account work runs.
 *
 * <p>Carries only fixed application text naming the field that was unusable - never the response
 * body, which may contain account data, and never the key.
 */
public class MalformedProofException extends Exception {
    public MalformedProofException(String message) {
        super(message);
    }
}
