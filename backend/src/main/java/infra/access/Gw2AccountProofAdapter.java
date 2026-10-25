package infra.access;

import application.access.AccountProof;
import application.access.AccountProofPort;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import application.access.ProofUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/**
 * Proves a transient GW2 API key against the live API (STORY-API-010, TARGET_ARCHITECTURE.md §8.1
 * and §11).
 *
 * <p>Deliberately not built on {@code api.Gw2ApiClient}: that client authenticates every call with
 * the locally configured key and collapses every non-200 status into one exception, while this
 * adapter must use the <em>caller's</em> key and must tell a definitive rejection apart from an
 * outage - the two outcomes have opposite consequences for a session.
 *
 * <p>The key appears only in the {@code Authorization} header of the two requests. It is never put
 * in a URL, a log record, an exception message or the returned {@link AccountProof}, and no response
 * body is quoted in an error: a body may contain account data. Redirects are refused, so a redirect
 * cannot carry the header to another host.
 */
public final class Gw2AccountProofAdapter implements AccountProofPort {

    private static final String DEFAULT_BASE_URL = "https://api.guildwars2.com";
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Clock clock;

    public Gw2AccountProofAdapter() {
        this(DEFAULT_BASE_URL, Clock.systemUTC());
    }

    public Gw2AccountProofAdapter(String baseUrl, Clock clock) {
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(UPSTREAM_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public AccountProof prove(String apiKey)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidCredentialException("no credential was supplied");
        }
        String key = apiKey.trim();
        JsonNode account = get("/v2/account", key);
        JsonNode tokenInfo = get("/v2/tokeninfo", key);
        return Gw2ProofResponses.parse(account, tokenInfo, clock);
    }

    private JsonNode get(String path, String key)
            throws InvalidCredentialException, MalformedProofException, ProofUnavailableException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(UPSTREAM_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProofUnavailableException("GW2 validation could not be reached",
                    Duration.ofSeconds(15), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProofUnavailableException("GW2 validation was interrupted", Duration.ofSeconds(15), e);
        }
        int status = response.statusCode();
        if (status == 200) {
            try {
                return mapper.readTree(response.body());
            } catch (IOException e) {
                throw new MalformedProofException("the GW2 validation response was not JSON");
            }
        }
        if (status == 400 || status == 401 || status == 403) {
            throw new InvalidCredentialException("GW2 rejected the credential");
        }
        if (status == 429) {
            throw new ProofUnavailableException("GW2 validation is rate limited", retryAfterOf(response));
        }
        if (status >= 500) {
            throw new ProofUnavailableException("GW2 validation is unavailable", Duration.ofSeconds(30));
        }
        throw new MalformedProofException("the GW2 validation response status was not understood");
    }

    /** Rate-limit guidance comes from the response when present, clamped by the exception itself. */
    private static Duration retryAfterOf(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(value.trim()));
                    } catch (NumberFormatException e) {
                        return Duration.ofSeconds(60);
                    }
                })
                .orElse(Duration.ofSeconds(60));
    }
}
