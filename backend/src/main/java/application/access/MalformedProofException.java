package application.access;

/**
 * An upstream validation response was not the documented shape, so nothing about the key was
 * established (STORY-API-010, TARGET_ARCHITECTURE.md §8.1: "Malformed validation responses fail
 * closed"). No session is issued and no account work runs.
 *
 * <p>Carries only fixed application text naming the field that was unusable - never the response
 * body, which may contain account data, and never the key.
 */
public class MalformedProofException extends Exception {
    public MalformedProofException(String message) {
        super(message);
    }
}
