package application.access;

import java.time.Instant;
import java.util.Set;

/**
 * What one successful proof of a transient GW2 key established (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1): the account's persistent GUID plus the effective limits of the key
 * that proved it. Deliberately carries no key, no key identifier and no account display name - only
 * the GUID is identity, and nothing here may become a credential.
 *
 * @param accountId            the {@code /v2/account} {@code id} GUID, the sole account identity
 * @param grants               the key's modelled {@code /v2/tokeninfo} permissions
 * @param endpointRestrictions the key's {@code urls} restriction, empty when unrestricted
 * @param credentialExpiresAt  the key's own expiry, or {@code null} when it does not expire
 */
public record AccountProof(String accountId,
                           Set<AccountGrant> grants,
                           Set<String> endpointRestrictions,
                           Instant credentialExpiresAt) {

    public AccountProof(String accountId, Set<AccountGrant> grants, Set<String> endpointRestrictions,
                        Instant credentialExpiresAt) {
        this.accountId = accountId;
        this.grants = Set.copyOf(grants);
        this.endpointRestrictions = Set.copyOf(endpointRestrictions);
        this.credentialExpiresAt = credentialExpiresAt;
    }
}
