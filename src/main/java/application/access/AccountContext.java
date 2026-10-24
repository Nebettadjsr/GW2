package application.access;

import java.time.Instant;
import java.util.Set;

/**
 * The trusted immutable account scope a live session resolves to (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1 "Request and application scope").
 *
 * <p>Only the HTTP boundary creates one, and only from a session it authenticated: no caller-supplied
 * account field, header or query parameter can produce or widen it. Application use cases receive it
 * explicitly rather than reading process-wide or thread-inherited state, so an admitted operation
 * keeps the scope it was admitted with even after its session ends.
 *
 * @param accountId            the verified account GUID
 * @param grants              the proving key's effective grants
 * @param endpointRestrictions the proving key's URL restriction, empty when unrestricted
 * @param accessContext        the non-secret identifier bound to this exact session
 * @param expiresAt            the session's absolute expiry; never extended by use
 */
public record AccountContext(String accountId,
                             Set<AccountGrant> grants,
                             Set<String> endpointRestrictions,
                             String accessContext,
                             Instant expiresAt) {

    public AccountContext(String accountId, Set<AccountGrant> grants, Set<String> endpointRestrictions,
                          String accessContext, Instant expiresAt) {
        this.accountId = accountId;
        this.grants = Set.copyOf(grants);
        this.endpointRestrictions = Set.copyOf(endpointRestrictions);
        this.accessContext = accessContext;
        this.expiresAt = expiresAt;
    }

    /** True only when every grant and endpoint the operation consumes is covered by this scope. */
    public boolean permits(AccountOperation operation) {
        if (!grants.containsAll(operation.requiredGrants())) return false;
        return endpointRestrictions.isEmpty()
                || endpointRestrictions.containsAll(operation.upstreamEndpoints());
    }

    /**
     * A caller-supplied account identifier is a consistency assertion only: it may agree with the
     * resolved scope, and it never selects one. Absent or blank asserts nothing.
     */
    public boolean assertsSameAccount(String assertedAccountId) {
        return assertedAccountId == null || assertedAccountId.isBlank()
                || assertedAccountId.equalsIgnoreCase(accountId);
    }
}
