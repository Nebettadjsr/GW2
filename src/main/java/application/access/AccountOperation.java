package application.access;

import java.util.Set;

/**
 * The account-scoped operations whose grant requirements this access foundation centralizes
 * (STORY-API-010, TARGET_ARCHITECTURE.md §8.1 "Request and application scope").
 *
 * <p>Each operation declares the grants of the account <em>sources</em> it consumes, not the grants
 * of whichever key happened to synchronize the stored rows: a session proved with a narrow
 * replacement key therefore cannot read data a more privileged key wrote. Endpoint restrictions are
 * declared the same way, so a {@code /v2/tokeninfo} {@code urls} list that excludes a source's
 * upstream endpoint excludes every operation consuming that source.
 *
 * <p>Only the operations this story guards are listed. The remaining HTTP workflows are integrated
 * by their own story; adding one here is a declaration, not enforcement, which lives at the
 * boundary that resolves the request context.
 */
public enum AccountOperation {

    /** Consumed Luck, stored from {@code /v2/account/luck}. */
    LUCK_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS),
            Set.of("/v2/account/luck")),

    /** Account bank slots, stored from {@code /v2/account/bank}. */
    BANK_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES), Set.of("/v2/account/bank")),

    /** Material storage quantities, stored from {@code /v2/account/materials}. */
    MATERIAL_STORAGE_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES),
            Set.of("/v2/account/materials")),

    /** Account recipe unlocks, stored from {@code /v2/account/recipes}. */
    RECIPES_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.UNLOCKS), Set.of("/v2/account/recipes")),

    /** The character roster and every character payload, stored from {@code /v2/characters}. */
    CHARACTERS_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS), Set.of("/v2/characters")),

    /** A full account refresh: every account source this application publishes. */
    ACCOUNT_REFRESH(Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
            AccountGrant.UNLOCKS, AccountGrant.PROGRESSION),
            Set.of("/v2/account", "/v2/account/bank", "/v2/account/materials", "/v2/account/recipes",
                    "/v2/account/luck", "/v2/characters"));

    private final Set<AccountGrant> requiredGrants;
    private final Set<String> upstreamEndpoints;

    AccountOperation(Set<AccountGrant> requiredGrants, Set<String> upstreamEndpoints) {
        this.requiredGrants = Set.copyOf(requiredGrants);
        this.upstreamEndpoints = Set.copyOf(upstreamEndpoints);
    }

    public Set<AccountGrant> requiredGrants() {
        return requiredGrants;
    }

    /** The GW2 endpoints this operation's sources come from, compared against key URL restrictions. */
    public Set<String> upstreamEndpoints() {
        return upstreamEndpoints;
    }
}
