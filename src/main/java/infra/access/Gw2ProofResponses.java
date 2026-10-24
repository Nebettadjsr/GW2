package infra.access;

import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one {@code /v2/account} and one {@code /v2/tokeninfo} response into an {@link AccountProof}
 * (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>Separate from the HTTP adapter so that the offline fixture adapter proves keys through exactly
 * this validation rather than through a second, more forgiving copy of it: shape checks, the GUID
 * requirement, permission mapping, URL restrictions and expiry all behave the same whether the JSON
 * came from ArenaNet or from a checked-in fixture.
 *
 * <p>No message produced here quotes a response body or a credential - a body may contain account
 * data, and the message is the one thing a caller could observe.
 */
public final class Gw2ProofResponses {

    private static final Pattern ACCOUNT_GUID =
            Pattern.compile("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}");

    private Gw2ProofResponses() {}

    public static AccountProof parse(JsonNode account, JsonNode tokenInfo, Clock clock)
            throws MalformedProofException, InvalidCredentialException {
        return new AccountProof(accountId(account), grants(tokenInfo), endpointRestrictions(tokenInfo),
                expiry(tokenInfo, clock));
    }

    /** Only the persistent account GUID is identity; a display name or the key's own id never is. */
    private static String accountId(JsonNode account) throws MalformedProofException {
        if (account == null || !account.isObject() || !account.path("id").isTextual()) {
            throw new MalformedProofException("the GW2 account response carried no account id");
        }
        String id = account.path("id").asText().trim();
        if (!ACCOUNT_GUID.matcher(id).matches()) {
            throw new MalformedProofException("the GW2 account id was not an account GUID");
        }
        return id;
    }

    private static Set<AccountGrant> grants(JsonNode tokenInfo) throws MalformedProofException {
        if (tokenInfo == null || !tokenInfo.isObject() || !tokenInfo.path("permissions").isArray()) {
            throw new MalformedProofException("the GW2 token info carried no permissions");
        }
        Set<AccountGrant> grants = new LinkedHashSet<>();
        for (JsonNode permission : tokenInfo.path("permissions")) {
            if (!permission.isTextual()) {
                throw new MalformedProofException("a GW2 token-info permission was not a name");
            }
            AccountGrant.fromApiName(permission.asText()).ifPresent(grants::add);
        }
        return grants;
    }

    /** An absent {@code urls} list means unrestricted; an unusable one fails closed. */
    private static Set<String> endpointRestrictions(JsonNode tokenInfo) throws MalformedProofException {
        JsonNode urls = tokenInfo.path("urls");
        if (urls.isMissingNode() || urls.isNull()) return Set.of();
        if (!urls.isArray()) throw new MalformedProofException("the GW2 token-info urls were unusable");
        Set<String> endpoints = new LinkedHashSet<>();
        for (JsonNode url : urls) {
            if (!url.isTextual()) throw new MalformedProofException("a GW2 token-info url was not a path");
            endpoints.add(url.asText().trim());
        }
        return endpoints;
    }

    /**
     * An expired subtoken is a definitively invalid credential, not a malformed response: it is a
     * well-formed answer that establishes no access.
     */
    private static Instant expiry(JsonNode tokenInfo, Clock clock)
            throws MalformedProofException, InvalidCredentialException {
        JsonNode expiresAt = tokenInfo.path("expires_at");
        if (expiresAt.isMissingNode() || expiresAt.isNull()) return null;
        if (!expiresAt.isTextual()) {
            throw new MalformedProofException("the GW2 token-info expiry was unusable");
        }
        Instant expiry;
        try {
            expiry = Instant.parse(expiresAt.asText().trim());
        } catch (DateTimeParseException e) {
            throw new MalformedProofException("the GW2 token-info expiry was not an instant");
        }
        if (!clock.instant().isBefore(expiry)) {
            throw new InvalidCredentialException("the credential has expired");
        }
        return expiry;
    }
}
