package application.access;

/**
 * The account-access use case: establish, renew, resolve and revoke validated account sessions, and
 * admit keyed account operations (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>Everything the access contract decides is decided here, once, so no route can re-decide it:
 * which responses count as identity, what a session may do, and which failures revoke it. The HTTP
 * boundary only maps the outcomes onto status codes and cookies.
 */
public final class AccountAccessService {

    private final AccountProofPort proof;
    private final AccountSessions sessions;

    public AccountAccessService(AccountProofPort proof, AccountSessions sessions) {
        this.proof = proof;
        this.sessions = sessions;
    }

    /** Raised when a proved key resolves to an account other than the one already authorized. */
    public static class AccountMismatchException extends Exception {
        public AccountMismatchException(String message) {
            super(message);
        }
    }

    /** Raised when a live session lacks the grants an operation consumes. */
    public static class InsufficientGrantsException extends Exception {
        public InsufficientGrantsException(String message) {
            super(message);
        }
    }

    /**
     * Proves a transient key and issues a fresh session for the account it resolves to.
     *
     * <p>The identity permission is required here as well as upstream: a response that somehow
     * arrived without it must not become a principal.
     */
    public AccountSessions.Issued establish(String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException {
        AccountProof proved = proof.prove(apiKey);
        if (!proved.grants().contains(AccountGrant.ACCOUNT)) {
            throw new InvalidCredentialException("the credential has no account identity permission");
        }
        return sessions.establish(proved);
    }

    /**
     * Renews access by repeating upstream validation with a presented key. There is no refresh
     * token: the caller must still hold the credential.
     *
     * <p>A renewal issues a <em>new</em> session rather than extending the presented one, so the
     * absolute lease is never lengthened. The presented session is deliberately left to run out its
     * own remaining lease - it is still the session a tab that has not yet seen the new cookie holds,
     * and §8.2 already expects an old cookie to stay usable until its lease expires. Logout is what
     * revokes early.
     */
    public AccountSessions.Issued renew(String sessionId, String assertedContext, String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException,
            AccountMismatchException {
        AccountSessions.Resolution current = sessions.resolve(sessionId, assertedContext);
        if (!current.authenticated()) {
            throw new IllegalStateException("renewal requires an authenticated session");
        }
        AccountProof proved;
        try {
            proved = proof.prove(apiKey);
        } catch (InvalidCredentialException e) {
            sessions.revoke(sessionId);
            throw e;
        }
        if (!proved.grants().contains(AccountGrant.ACCOUNT)) {
            sessions.revoke(sessionId);
            throw new InvalidCredentialException("the credential has no account identity permission");
        }
        if (!proved.accountId().equalsIgnoreCase(current.context().accountId())) {
            throw new AccountMismatchException("the credential resolves to a different account");
        }
        return sessions.establish(proved);
    }

    /** Resolves a presented cookie and asserted context; no upstream call, so an outage is fine. */
    public AccountSessions.Resolution resolve(String sessionId, String assertedContext) {
        return sessions.resolve(sessionId, assertedContext);
    }

    /**
     * Revokes a session server-side. The caller must have resolved the exact session first, so a
     * stale tab asserting another context cannot revoke it.
     */
    public boolean logout(String sessionId) {
        return sessions.revoke(sessionId);
    }

    /**
     * A server-owned capability that revokes only the presented session, for a worker that later
     * learns the admitted key was definitively rejected. It holds no raw session identifier.
     */
    public Runnable revocationOf(String sessionId) {
        return sessions.revocationOf(sessionId);
    }

    /**
     * The outcome of proving one transient key for one session: the credential's own scope. It holds
     * no key and is only meaningful for the request that obtained it, so a single proof can be
     * checked against several operations (and reused by a later step of the same request) without
     * asking GW2 again.
     */
    public record KeyedProof(AccountContext credentialScope) {
    }

    /**
     * Admits an account operation that will call GW2 with a caller-supplied transient key
     * (TARGET_ARCHITECTURE.md §8.1: "a session is not an upstream credential").
     *
     * <p>Revalidates the key before any upstream work: it must resolve to the session's account and
     * cover the operation's grants, and the live session must cover them too, so a narrow session
     * cannot borrow a wide key's privileges or the other way round. A definitive invalid-credential
     * answer revokes the session; a transient upstream failure leaves it alone.
     *
     * @param sessionId the presented session identifier, needed only so an invalid key can revoke it
     */
    public void authorizeKeyedOperation(String sessionId, AccountContext context, String apiKey,
                                       AccountOperation operation)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException,
            AccountMismatchException, InsufficientGrantsException {
        authorizeKeyedOperations(sessionId, context, apiKey, java.util.List.of(operation));
    }

    /** Proves the key once and checks every operation against that one proof. */
    public KeyedProof authorizeKeyedOperations(String sessionId, AccountContext context, String apiKey,
                                               java.util.Collection<AccountOperation> operations)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException,
            AccountMismatchException, InsufficientGrantsException {
        // Fail on the session before the upstream key check, as for a single operation.
        AccountContext live = liveContext(sessionId, context);
        for (AccountOperation operation : operations) requireSessionPermits(live, operation);
        KeyedProof proof = proveKey(sessionId, live, apiKey);
        authorizeKeyedOperations(sessionId, context, proof, operations);
        return proof;
    }

    /**
     * Checks {@code operations} against a proof already obtained for this request. The session is
     * resolved again (it may have ended since) and the account and grant checks all run; only the
     * upstream key validation is not repeated.
     */
    public void authorizeKeyedOperations(String sessionId, AccountContext context, KeyedProof proof,
                                         java.util.Collection<AccountOperation> operations)
            throws AccountMismatchException, InsufficientGrantsException {
        AccountContext live = liveContext(sessionId, context);
        if (!proof.credentialScope().accountId().equalsIgnoreCase(live.accountId())) {
            throw new AccountMismatchException("the credential resolves to a different account");
        }
        for (AccountOperation operation : operations) {
            requireSessionPermits(live, operation);
            if (!proof.credentialScope().permits(operation)) {
                throw new InsufficientGrantsException("the credential does not cover this operation");
            }
        }
    }

    // The presented session must be live and the context must be the one issued for it: a
    // fabricated, expired or revoked pair never reaches the upstream key check. The scope that
    // was stored at proof time is authoritative, not the caller's copy of it.
    private AccountContext liveContext(String sessionId, AccountContext context) {
        AccountSessions.Resolution live = sessions.resolve(sessionId, context.accessContext());
        if (!live.authenticated()) {
            throw new IllegalStateException("the session is not live or the context was not issued for it");
        }
        return live.context();
    }

    private static void requireSessionPermits(AccountContext live, AccountOperation operation)
            throws InsufficientGrantsException {
        if (!live.permits(operation)) {
            throw new InsufficientGrantsException("the session does not cover this operation");
        }
    }

    private KeyedProof proveKey(String sessionId, AccountContext live, String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException {
        AccountProof proved;
        try {
            proved = proof.prove(apiKey);
        } catch (InvalidCredentialException e) {
            sessions.revoke(sessionId);
            throw e;
        }
        return new KeyedProof(new AccountContext(proved.accountId(), proved.grants(),
                proved.endpointRestrictions(), live.accessContext(), live.expiresAt()));
    }
}
