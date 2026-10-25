package application.access;

/**
 * Validates one transient GW2 API key upstream (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>An implementation must call {@code /v2/account} and {@code /v2/tokeninfo} with the <em>same</em>
 * key and report only what those two responses establish. The three failure modes are kept distinct
 * because the access boundary answers them differently: a definitive invalid credential is not
 * retryable and revokes an associated session, an unavailable upstream is retryable and revokes
 * nothing, and a response that cannot be understood fails closed.
 */
public interface AccountProofPort {

    /**
     * @param apiKey the caller's transient key; held only for this call and never stored
     * @throws InvalidCredentialException the key is definitively rejected or lacks account identity
     * @throws MalformedProofException    a response could not be understood; access fails closed
     * @throws ProofUnavailableException  upstream validation could not be completed; retryable
     */
    AccountProof prove(String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException;
}
