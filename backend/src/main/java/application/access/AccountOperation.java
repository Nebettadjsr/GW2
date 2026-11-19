package application.access;

import java.util.Set;

/**
 * Account-scoped operations with their required grants and upstream endpoints.
 *
 * <p>Each operation declares the grants of the account <em>sources</em> it consumes, not the grants
 * of whichever key happened to synchronize the stored rows: a session proved with a narrow
 * replacement key therefore cannot read data a more privileged key wrote. Endpoint restrictions are
 * declared the same way, so a {@code /v2/tokeninfo} {@code urls} list that excludes a source's
 * upstream endpoint excludes every operation consuming that source.
 *
 * <p>Adding an operation here declares its requirements; the consuming boundary must enforce them
 * when resolving the request context.
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

    /**
     * The character roster and every character payload, stored from {@code /v2/characters}. The
     * published payload includes bags and equipment, which the API only returns to keys that also
     * hold {@code inventories} (https://wiki.guildwars2.com/wiki/API:2/tokeninfo), so that grant is
     * required next to {@code characters}.
     */
    CHARACTERS_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES),
            Set.of("/v2/characters")),

    /**
     * The saved-equipment-tabs endpoint itself, {@code /v2/characters/:id/equipmenttabs}: it needs
     * {@code account}, {@code builds} and {@code characters}. {@code inventories} is deliberately not
     * attributed to it (STORY-CHAR-005).
     */
    CHARACTER_EQUIPMENT_TABS_ENDPOINT(
            Set.of(AccountGrant.ACCOUNT, AccountGrant.BUILDS, AccountGrant.CHARACTERS),
            Set.of("/v2/characters/:id/equipmenttabs")),

    /**
     * The first-iteration equipment-tabs workflow (refresh and read): the endpoint's own dependency
     * plus the cached {@code CHARACTERS} parent it enumerates and is read next to, which stays
     * governed by {@link #CHARACTERS_READ} ({@code inventories}). The four-grant union applies only to
     * this workflow; it is never added to {@link #CHARACTERS_READ} or any existing consumer.
     */
    CHARACTER_EQUIPMENT_TABS_READ(
            Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES, AccountGrant.BUILDS),
            Set.of("/v2/characters", "/v2/characters/:id/equipmenttabs")),

    /** The saved-build-tabs endpoint itself, {@code /v2/characters/:id/buildtabs} (STORY-CHAR-007). */
    CHARACTER_BUILD_TABS_ENDPOINT(
            Set.of(AccountGrant.ACCOUNT, AccountGrant.BUILDS, AccountGrant.CHARACTERS),
            Set.of("/v2/characters/:id/buildtabs")),

    /**
     * The build-tabs workflow (refresh and read): the endpoint's own dependency plus the cached
     * {@code CHARACTERS} parent it enumerates, which stays governed by {@link #CHARACTERS_READ}. The union
     * applies only to this workflow and is never added to any existing consumer.
     */
    CHARACTER_BUILD_TABS_READ(
            Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES, AccountGrant.BUILDS),
            Set.of("/v2/characters", "/v2/characters/:id/buildtabs")),

    /**
     * Crafting Profit's account inputs: bank, material storage, recipe unlocks and the character
     * roster. Luck is deliberately absent - Profit never consumes it (DOMAIN_SPEC), so a key without
     * {@code progression} may still use Profit.
     */
    CRAFTING_PROFIT_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
            AccountGrant.UNLOCKS),
            Set.of("/v2/account/bank", "/v2/account/materials", "/v2/account/recipes", "/v2/characters")),

    /** Account wallet balances, stored from {@code /v2/account/wallet}; consumed only by currency recipes. */
    WALLET_READ(Set.of(AccountGrant.ACCOUNT, AccountGrant.WALLET), Set.of("/v2/account/wallet")),

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
