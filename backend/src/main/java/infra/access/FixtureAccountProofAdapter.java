package infra.access;

import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import application.access.ProofUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;

/**
 * An offline stand-in for {@link Gw2AccountProofAdapter}, enabled only by
 * {@code gw2.account-access.test-fixtures} (STORY-API-010, TEST_STRATEGY.md §31.3).
 *
 * <p>It exists because the access contract - cookie policy, origin/CSRF, access-context binding,
 * grant rejection and the status mapping - is only observable over HTTP, and no automated layer may
 * send a real credential to ArenaNet. Every proof here is resolved from the checked-in trimmed
 * response fixtures through {@link Gw2ProofResponses}, the same validation the live adapter uses, so
 * a fixture that is not the documented shape fails exactly as a real malformed response would.
 *
 * <p>Default-off: with the property unset the application wires the live adapter and this class is
 * never constructed. The fixtures it reads live in test resources and are absent from a packaged
 * application, so enabling it there fails loudly instead of granting access.
 *
 * <p>The keys are fixed names, never credentials. {@code fixture-key-restricted} resolves to the
 * <em>same</em> account as {@code fixture-key-a} with a narrower permission set, which is what makes
 * "a replacement key keeps its own grants rather than inheriting the previous key's" observable. Any
 * name not listed below is a definitive rejection, like an unknown key upstream.
 */
public final class FixtureAccountProofAdapter implements AccountProofPort {

    public static final String KEY_FULL = "fixture-key-a";
    public static final String KEY_SECOND_ACCOUNT = "fixture-key-b";
    public static final String KEY_RESTRICTED = "fixture-key-restricted";
    public static final String KEY_EXPIRED = "fixture-key-expired";
    public static final String KEY_MALFORMED = "fixture-key-malformed";
    public static final String KEY_UNAVAILABLE = "fixture-key-unavailable";
    public static final String KEY_RATE_LIMITED = "fixture-key-rate-limited";

    private static final String ACCOUNT_FIXTURE = "web/access/account-response.json";
    private static final String TOKEN_INFO_FIXTURE = "web/access/tokeninfo-response.json";
    private static final String EXPIRED_TOKEN_INFO_FIXTURE = "web/access/tokeninfo-expired-subtoken.json";

    /**
     * The second fixture account reuses the documented account shape with a different GUID: only the
     * GUID establishes identity, so that is the single field two fixture accounts need to differ in.
     */
    private static final String SECOND_ACCOUNT_GUID = "D29467C6-F5AD-E211-8756-78E7D1936222";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public FixtureAccountProofAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AccountProof prove(String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException {
        String key = apiKey == null ? "" : apiKey.trim();
        return switch (key) {
            case KEY_FULL -> Gw2ProofResponses.parse(account(), tokenInfo(), clock);
            case KEY_SECOND_ACCOUNT ->
                    Gw2ProofResponses.parse(accountWithId(SECOND_ACCOUNT_GUID), tokenInfo(), clock);
            case KEY_RESTRICTED ->
                    Gw2ProofResponses.parse(account(), tokenInfoWithOnlyIdentityPermission(), clock);
            case KEY_EXPIRED -> Gw2ProofResponses.parse(account(), expiredTokenInfo(), clock);
            case KEY_MALFORMED -> Gw2ProofResponses.parse(accountWithoutId(), tokenInfo(), clock);
            case KEY_UNAVAILABLE -> throw new ProofUnavailableException(
                    "GW2 validation is unavailable", Duration.ofSeconds(30));
            case KEY_RATE_LIMITED -> throw new ProofUnavailableException(
                    "GW2 validation is rate limited", Duration.ofSeconds(60));
            default -> throw new InvalidCredentialException("GW2 rejected the credential");
        };
    }

    private JsonNode account() {
        return load(ACCOUNT_FIXTURE);
    }

    private JsonNode tokenInfo() {
        return load(TOKEN_INFO_FIXTURE);
    }

    private JsonNode expiredTokenInfo() {
        return load(EXPIRED_TOKEN_INFO_FIXTURE);
    }

    private JsonNode accountWithId(String accountId) {
        ObjectNode account = (ObjectNode) load(ACCOUNT_FIXTURE);
        account.put("id", accountId);
        return account;
    }

    private JsonNode accountWithoutId() {
        ObjectNode account = (ObjectNode) load(ACCOUNT_FIXTURE);
        account.remove("id");
        return account;
    }

    private JsonNode tokenInfoWithOnlyIdentityPermission() {
        ObjectNode tokenInfo = (ObjectNode) load(TOKEN_INFO_FIXTURE);
        ArrayNode permissions = tokenInfo.putArray("permissions");
        permissions.add("account");
        return tokenInfo;
    }

    /** Freshly parsed per call, so one caller's adjustment cannot leak into the next. */
    private JsonNode load(String resource) {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("account-access fixtures are enabled but " + resource
                        + " is not on the classpath");
            }
            return mapper.readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("account-access fixture " + resource + " is unreadable", e);
        }
    }
}
