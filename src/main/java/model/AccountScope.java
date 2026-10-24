package model;

import java.util.Objects;
import java.util.UUID;

/**
 * The explicit trusted account scope every account-owned persistence operation requires
 * (STORY-APP-015, TARGET_ARCHITECTURE.md §7.2 "All account repository operations require explicit
 * trusted scope", §8.1 "Request and application scope").
 *
 * <p>It carries exactly one thing: the verified {@code /v2/account} GUID that is the account
 * registry's primary key. Not a credential, not a session, not a grant set - those belong to the
 * access boundary that resolves a live session into a scope and passes it in. Holding only the GUID
 * is what lets persistence, publication and the calculation snapshot require scope without gaining
 * an authentication dependency, and what lets a replacement key for the same account reuse the
 * existing dataset (§7 "keyed by the stable Guild Wars 2 account identity, not by a particular API
 * key").
 *
 * <p>There is deliberately no no-argument factory, no "current", "configured" or "last used"
 * accessor and no mutable process-wide holder: a scope can only be constructed from an identifier a
 * caller already verified, so no code path can acquire one by default. A caller-supplied account
 * field is a consistency assertion at the HTTP boundary, never an input to this type.
 *
 * <p>That is why a workflow with no scope is split rather than given one (STORY-SYNC-007): its
 * global half takes no {@code AccountScope} parameter at all, so "no scope" is a selection over
 * shared data and never a stand-in for "some account" - the absent scope cannot be narrowed to the
 * only registered account, the last synchronized one, or any other default.
 *
 * <p>Lives in {@code model} because both the persistence adapters and the application use cases
 * need it: it is a dependency-free value record, so requiring it inverts no layer and adds no JDBC,
 * HTTP or framework dependency anywhere it is used. Domain calculations never see it - they receive
 * data already scoped by the repositories.
 */
public record AccountScope(UUID accountId) {

    public AccountScope {
        Objects.requireNonNull(accountId, "accountId is required: account data has no default scope");
    }

    public static AccountScope of(UUID accountId) {
        return new AccountScope(accountId);
    }

    /**
     * The scope for an already-verified account GUID in its textual form, as {@code /v2/account}
     * returns it and as the access boundary carries it.
     *
     * @throws IllegalArgumentException when absent, blank or not a GUID - an unparseable identifier
     *                                 is never narrowed to "some account"
     */
    public static AccountScope of(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required: account data has no default scope");
        }
        try {
            return new AccountScope(UUID.fromString(accountId.trim()));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("accountId is not a verified account GUID", malformed);
        }
    }

    /** The GUID in its canonical textual form, for diagnostics and {@code uuid} bind parameters. */
    public String id() {
        return accountId.toString();
    }
}
