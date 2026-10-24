package application.access;

/**
 * Upstream definitively rejected the credential, or it cannot establish account identity
 * (STORY-API-010). Not retryable: it revokes an associated session and maps to 401.
 *
 * <p>The message is fixed application text. Neither the key nor an upstream response body may reach
 * it, because this exception is logged and its text is the one thing a caller could observe.
 */
public class InvalidCredentialException extends Exception {
    public InvalidCredentialException(String message) {
        super(message);
    }
}
