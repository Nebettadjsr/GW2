package application.access;

import java.util.Arrays;
import java.util.Optional;

/**
 * One Guild Wars 2 API-key permission, as reported by {@code /v2/tokeninfo} (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>Grants are captured from the proved key and never widened: an operation is admitted only when
 * every grant it consumes is present. A permission this application does not model is ignored
 * rather than mapped onto a neighbouring one, so an unknown upstream permission can never satisfy a
 * requirement.
 */
public enum AccountGrant {
    /** The identity permission; without it {@code /v2/account} cannot be read at all. */
    ACCOUNT("account"),
    INVENTORIES("inventories"),
    CHARACTERS("characters"),
    UNLOCKS("unlocks"),
    PROGRESSION("progression"),
    WALLET("wallet"),
    BUILDS("builds"),
    TRADINGPOST("tradingpost"),
    GUILDS("guilds"),
    PVP("pvp");

    private final String apiName;

    AccountGrant(String apiName) {
        this.apiName = apiName;
    }

    /** The lower-case name the GW2 API uses in its {@code permissions} array. */
    public String apiName() {
        return apiName;
    }

    /** Empty for a permission this application does not model, never a nearest match. */
    public static Optional<AccountGrant> fromApiName(String name) {
        if (name == null) return Optional.empty();
        String trimmed = name.trim();
        return Arrays.stream(values()).filter(grant -> grant.apiName.equalsIgnoreCase(trimmed)).findFirst();
    }
}
