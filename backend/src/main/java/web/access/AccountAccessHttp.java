package web.access;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The transport-level rules of the account-access contract in one place (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1 and §8.2): the names the browser must use, same-origin/CSRF
 * validation of state-changing requests, and the shape of the session cookie.
 *
 * <p>Kept separate from the controller and the interceptor because both apply exactly these rules
 * and they must not drift: a session route that validated the origin differently from the guard
 * would be the gap the guard exists to close.
 */
public final class AccountAccessHttp {

    /** Host-only session cookie; its value is opaque and is never read by script. */
    public static final String SESSION_COOKIE = "GW2_SESSION";

    /** The non-secret per-session assertion a tab sends with every account request. */
    public static final String ACCESS_CONTEXT_HEADER = "X-Account-Context";

    /** The only place a GW2 API key is accepted; never a URL, body field or cookie. */
    public static final String API_KEY_HEADER = "X-GW2-API-Key";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final List<String> configuredOrigins;

    /**
     * @param allowedOrigins optional comma-separated deployment origins; the request's own origin is
     *                       always accepted, so an ordinary same-origin deployment configures nothing
     */
    public AccountAccessHttp(String allowedOrigins) {
        this.configuredOrigins = allowedOrigins == null || allowedOrigins.isBlank()
                ? List.of()
                : Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                        .filter(origin -> !origin.isEmpty()).toList();
    }

    /**
     * True only when the request carries an {@code Origin} this backend serves.
     *
     * <p>A missing {@code Origin} is rejected, not trusted: for a state-changing request it is the
     * evidence that a browser considered the call same-origin, so its absence has to fail closed.
     */
    public boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return false;
        String normalized = origin.trim();
        return normalized.equalsIgnoreCase(originOf(request)) || configuredOrigins.stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(normalized));
    }

    /** The origin this request was served on, with the default port omitted as browsers omit it. */
    private static String originOf(HttpServletRequest request) {
        String scheme = request.getScheme().toLowerCase(Locale.ROOT);
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    /**
     * Builds the {@code Set-Cookie} value for an issued session: host-only (no {@code Domain}),
     * {@code HttpOnly}, {@code SameSite=Strict} and {@code Secure} outside explicit localhost
     * development. No {@code Max-Age}, so the cookie never outlives the browser session either.
     */
    public String sessionCookie(HttpServletRequest request, String sessionId) {
        return SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict"
                + (requiresSecureCookie(request) ? "; Secure" : "");
    }

    /** Clears the cookie with the same attributes, so a browser cannot keep a revoked identifier. */
    public String clearedSessionCookie(HttpServletRequest request) {
        return SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
                + (requiresSecureCookie(request) ? "; Secure" : "");
    }

    /**
     * {@code Secure} is omitted only for plain HTTP on a loopback host - the explicit localhost
     * development case §8.1 permits. Any other plain-HTTP origin still gets {@code Secure}, so a
     * misconfigured deployment loses the cookie instead of leaking it.
     */
    private static boolean requiresSecureCookie(HttpServletRequest request) {
        if (request.isSecure()) return true;
        return !LOOPBACK_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT));
    }

    /** The presented session identifier, taken from the cookie only - never from a URL or a body. */
    public static Optional<String> presentedSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(cookie -> SESSION_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** The asserted access context, or {@code null} when the caller asserted none. */
    public static String assertedAccessContext(HttpServletRequest request) {
        String asserted = request.getHeader(ACCESS_CONTEXT_HEADER);
        return asserted == null || asserted.isBlank() ? null : asserted.trim();
    }

    /** The transient credential, or {@code null}; trimmed, never logged and never retained here. */
    public static String presentedApiKey(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        return key == null || key.isBlank() ? null : key.trim();
    }
}
