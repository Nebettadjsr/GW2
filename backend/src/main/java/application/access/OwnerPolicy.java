package application.access;

import java.util.Locale;
import java.util.UUID;

/**
 * The single optional server-side owner identity (STORY-STATUS-001): owner privilege belongs to a
 * live, backend-verified session whose stable account GUID equals the configured GUID, and to
 * nobody else. Unset or blank configuration grants no one; a malformed nonblank value fails at
 * construction instead of silently granting or denying access. Nothing client-supplied is consulted.
 */
public final class OwnerPolicy {

    private static final java.util.regex.Pattern CANONICAL = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final String ownerAccountId;

    /** @param configuredGuid the configured owner account GUID; null or blank means no owner */
    public OwnerPolicy(String configuredGuid) {
        this.ownerAccountId = configuredGuid == null || configuredGuid.isBlank()
                ? null : normalize(configuredGuid.trim());
    }

    /** True only for a verified context whose account GUID is exactly the configured owner. */
    public boolean isOwner(AccountContext verified) {
        if (ownerAccountId == null || verified == null || verified.accountId() == null) return false;
        try {
            return ownerAccountId.equals(normalize(verified.accountId().trim()));
        } catch (IllegalArgumentException notAGuid) {
            return false;
        }
    }

    private static String normalize(String guid) {
        if (!CANONICAL.matcher(guid).matches()) {
            throw new IllegalArgumentException("gw2.owner.account-guid must be a GUID");
        }
        try {
            String lower = guid.toLowerCase(Locale.ROOT);
            if (!UUID.fromString(lower).toString().equals(lower)) throw new IllegalArgumentException();
            return lower;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("gw2.owner.account-guid must be a GUID");
        }
    }
}
