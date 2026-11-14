package web.access;

import application.access.AccountAccessService;
import application.access.AccountContext;
import application.access.AccountGrant;
import application.access.AccountLifecycleGate;
import java.util.UUID;
import application.access.AccountOperation;
import application.access.AccountSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.http.server.PathContainer;
import tools.jackson.databind.ObjectMapper;
import web.dto.ApiErrorResponse;
import web.dto.SessionAccessResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The reusable account-authorization boundary: a live session and a matching access context before
 * any account work (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>Runs ahead of every other account interceptor, which is what "authorization precedes account
 * lookup, cache access, calculation and task admission" means in practice - a rejected request is
 * answered here, so no connection is opened, no freshness is consulted and no refresh is admitted on
 * its behalf. Nothing it answers names an account, says whether one exists, or reports freshness.
 *
 * <p>Two configuration switches, both default-off:
 *
 * <ul>
 *   <li>{@code gw2.account-access.enforce} turns the guard on. It is off by default because this
 *       story delivers the facility, not the migration of the existing single-user routes: enabling
 *       it for them is the integration story's deliverable, and flipping it here would break the
 *       running browser UI before it has a credential gate.</li>
 *   <li>{@code gw2.account-access.test-fixtures} additionally makes an authorized account request
 *       answer from this boundary instead of reaching its controller. That is how the HTTP contract
 *       suite exercises the guard's accept path offline: the real routes would need ArenaNet and a
 *       populated database, which no automated layer may use. It implies enforcement and is never
 *       set in a deployment.</li>
 * </ul>
 */
public final class AccountAccessInterceptor implements HandlerInterceptor {

    private static final String ACCOUNT_PATH_PREFIX = "/api/account/";
    private static final String ADMISSION_ATTRIBUTE = AccountAccessInterceptor.class.getName() + ".admission";
    private static final String ASSERTED_ACCOUNT_PARAMETER = "accountId";

    private final AccountAccessService access;

    /**
     * The application's own response mapper, so a body written here is shaped exactly like the one a
     * controller would have written. This is Spring Boot 4's auto-configured Jackson 3 mapper, not the
     * Jackson 2 {@code com.fasterxml} one {@code infra.access} parses GW2 responses with - the two
     * coexist on the classpath and only this one serializes the web layer.
     */
    private final ObjectMapper mapper;
    private final boolean enforce;
    private final boolean answerAuthorizedRequestsFromFixtures;

    public AccountAccessInterceptor(AccountAccessService access, ObjectMapper mapper, boolean enforce,
                                    boolean answerAuthorizedRequestsFromFixtures) {
        this.access = access;
        this.mapper = mapper;
        this.enforce = enforce || answerAuthorizedRequestsFromFixtures;
        this.answerAuthorizedRequestsFromFixtures = answerAuthorizedRequestsFromFixtures;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = authorizationPath(request);
        boolean deletionRoute = isDeletionRoute(path);
        boolean craftingRoute = isScopedCraftingRoute(path) || isScopedAccountRead(path)
                || isScopedAccountSync(path) || deletionRoute;
        if (!path.startsWith(ACCOUNT_PATH_PREFIX) && !craftingRoute) return true;
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // The crafting routes have no unscoped implementation left to fall back to (the versioned
        // schema refuses it), so the guard is not optional for them.
        if (!enforce && !craftingRoute) return true;

        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty()) return deny(response, HttpStatus.UNAUTHORIZED);
        String asserted = AccountAccessHttp.assertedAccessContext(request);
        AccountSessions.Resolution resolution = access.resolve(presented.get(), asserted);
        if (!resolution.authenticated()) return denyResolution(response, resolution, deletionRoute);

        AccountContext context = resolution.context();
        if (!deletionRoute) {
            // The shared admission is held for the whole request, so deletion cannot commit while
            // this request may still record authorized use. Authority is rechecked after admission:
            // a session deleted while this request waited must not resume as an authorized one.
            AccountLifecycleGate.Admission admission;
            try {
                admission = access.lifecycle().enter(UUID.fromString(context.accountId()));
            } catch (AccountLifecycleGate.Unavailable busy) {
                return unavailable(response);
            }
            request.setAttribute(ADMISSION_ATTRIBUTE, admission);
            resolution = access.resolve(presented.get(), asserted);
            if (!resolution.authenticated()) {
                release(request);
                return denyResolution(response, resolution, false);
            }
            context = resolution.context();
        }
        try {
            if (!context.assertsSameAccount(assertedAccountId(request))) {
                release(request);
                return deny(response, HttpStatus.FORBIDDEN);
            }
            AccountOperation operation = operationFor(path);
            if (operation != null && !context.permits(operation)) {
                release(request);
                return deny(response, HttpStatus.FORBIDDEN);
            }

            RequestAccountContext.publish(request, context);
            response.setHeader(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext());
            if (answerAuthorizedRequestsFromFixtures && !deletionRoute) {
                release(request);
                writeAuthorizedFixtureAnswer(response, context);
                return false;
            }
            return true;
        } catch (IOException | RuntimeException failed) {
            // Spring skips afterCompletion when preHandle throws, so the admission must not leak.
            release(request);
            throw failed;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        release(request);
    }

    private static void release(HttpServletRequest request) {
        if (request.getAttribute(ADMISSION_ATTRIBUTE) instanceof AccountLifecycleGate.Admission held) {
            request.removeAttribute(ADMISSION_ATTRIBUTE);
            held.close();
        }
    }

    private boolean denyResolution(HttpServletResponse response, AccountSessions.Resolution resolution,
                                   boolean deletionRoute) throws IOException {
        return switch (resolution.outcome()) {
            case DELETION_INVALIDATED -> writeError(response, HttpStatus.UNAUTHORIZED,
                    new ApiErrorResponse("ACCOUNT_DATA_DELETED", "This account's data was deleted; connect again to continue"));
            case CONTEXT_MISMATCH -> deletionRoute
                    ? writeError(response, HttpStatus.FORBIDDEN, new ApiErrorResponse("ACCESS_CONTEXT_MISMATCH",
                            "This request's access context does not belong to the authenticated session"))
                    : deny(response, HttpStatus.FORBIDDEN);
            default -> deny(response, HttpStatus.UNAUTHORIZED);
        };
    }

    private boolean unavailable(HttpServletResponse response) throws IOException {
        return writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                new ApiErrorResponse("DATA_STORE_UNAVAILABLE", "The account is busy; try again shortly"));
    }

    private boolean writeError(HttpServletResponse response, HttpStatus status, ApiErrorResponse body)
            throws IOException {
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
        return false;
    }

    static boolean isDeletionRoute(String path) {
        return path.equals("/api/account/data");
    }

    static boolean isDiscoveryRoute(String path) {
        return path.equals("/api/crafting/discovery") || path.equals("/api/crafting/discovery/resolution");
    }

    /** Keyed account refresh triggers; they admit work only for a verified, granted session. */
    static boolean isScopedAccountSync(String path) {
        return path.equals("/api/sync/account") || path.equals("/api/sync/account/crafting-profit")
                || path.equals("/api/sync/account/bank")
                || path.equals("/api/sync/account/materials")
                || path.equals("/api/sync/account/characters")
                || path.equals("/api/sync/account/luck")
                || path.equals("/api/sync/account/character-equipment-tabs")
                || path.equals("/api/sync/account/character-build-tabs");
    }

    /** Account reads that read only scoped storage and so cannot run without a resolved scope. */
    private static boolean isScopedAccountRead(String path) {
        return path.equals("/api/account/bank") || path.equals("/api/account/materials")
                || path.equals("/api/account/luck")
                || path.equals("/api/account/characters") || path.startsWith("/api/account/characters/");
    }

    /** The account-dependent crafting routes (STORY-API-011); other crafting routes are global. */
    private static boolean isScopedCraftingRoute(String path) {
        return path.equals("/api/crafting/profit") || path.equals("/api/crafting/profit/resolution")
                || path.equals("/api/crafting/selector-options") || isDiscoveryRoute(path);
    }

    /** Use the same decoded segments without matrix parameters that Spring maps to controllers. */
    private static String authorizationPath(HttpServletRequest request) {
        var parsed = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request);
        StringBuilder path = new StringBuilder();
        for (var element : parsed.pathWithinApplication().elements()) {
            path.append(element instanceof PathContainer.PathSegment segment
                    ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }

    /**
     * The grants an account route consumes. An undeclared path under {@code /api/account/} still
     * needs a live session and a matching context; it simply has no grant requirement to check yet,
     * so adding a route cannot silently become unauthenticated.
     */
    private static AccountOperation operationFor(String path) {
        return switch (path) {
            case "/api/account/luck" -> AccountOperation.LUCK_READ;
            case "/api/account/bank" -> AccountOperation.BANK_READ;
            case "/api/account/materials" -> AccountOperation.MATERIAL_STORAGE_READ;
            // /api/account/characters[/{name}] deliberately declare no operation here: the read
            // reports GRANT_REQUIRED / ENDPOINT_RESTRICTED as explicit states (STORY-CHAR-001), still
            // against AccountOperation.CHARACTERS_READ, and still behind a live session.
            case "/api/crafting/profit", "/api/crafting/profit/resolution" -> AccountOperation.CRAFTING_PROFIT_READ;
            case "/api/crafting/selector-options" -> AccountOperation.CHARACTERS_READ;
            case "/api/crafting/discovery", "/api/crafting/discovery/resolution" -> AccountOperation.CRAFTING_PROFIT_READ;
            case "/api/sync/account" -> AccountOperation.ACCOUNT_REFRESH;
            case "/api/sync/account/crafting-profit" -> AccountOperation.CRAFTING_PROFIT_READ;
            case "/api/sync/account/bank" -> AccountOperation.BANK_READ;
            case "/api/sync/account/materials" -> AccountOperation.MATERIAL_STORAGE_READ;
            case "/api/sync/account/characters" -> AccountOperation.CHARACTERS_READ;
            case "/api/sync/account/luck" -> AccountOperation.LUCK_READ;
            case "/api/sync/account/character-equipment-tabs" -> AccountOperation.CHARACTER_EQUIPMENT_TABS_READ;
            case "/api/sync/account/character-build-tabs" -> AccountOperation.CHARACTER_BUILD_TABS_READ;
            default -> null;
        };
    }

    /**
     * Reads the asserted account from the query string first, which is the only place a browser can
     * put it on the account reads this guards, and falls back to the request's parsed parameters for
     * a caller that supplied it some other way.
     *
     * <p>The fallback is skipped for a form-encoded request: {@code getParameter} would consume the
     * body the controller still has to read. Asserting nothing is the safe outcome, since a
     * caller-supplied account identifier can only ever agree with the resolved scope, never select
     * one.
     */
    private static String assertedAccountId(HttpServletRequest request) {
        String fromQueryString = assertedAccountIdInQueryString(request);
        if (fromQueryString != null) return fromQueryString;
        if (isFormEncoded(request)) return null;
        return blankAsNull(request.getParameter(ASSERTED_ACCOUNT_PARAMETER));
    }

    private static String assertedAccountIdInQueryString(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            if (!ASSERTED_ACCOUNT_PARAMETER.equals(name) || separator < 0) continue;
            return blankAsNull(URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return null;
    }

    private static boolean isFormEncoded(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT)
                .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    /** A blank assertion asserts nothing, exactly like an absent one. */
    private static String blankAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * One fixed answer per status, so a caller cannot tell a missing account from a foreign one, an
     * unknown session from an expired one, or a missing grant from a mismatched account assertion.
     */
    private boolean deny(HttpServletResponse response, HttpStatus status) throws IOException {
        ApiErrorResponse body = status == HttpStatus.UNAUTHORIZED
                ? new ApiErrorResponse("SESSION_REQUIRED", "Prove a GW2 API key to continue")
                : new ApiErrorResponse("ACCOUNT_ACCESS_DENIED",
                        "This session is not authorized for this account operation");
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
        return false;
    }

    /**
     * The authorized-request answer of the offline contract mode: it reports the access context the
     * request was authorized under and nothing else, since the point is the decision, not the data.
     */
    private void writeAuthorizedFixtureAnswer(HttpServletResponse response, AccountContext context)
            throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        List<String> grants = context.grants().stream().map(AccountGrant::apiName).sorted().toList();
        response.getWriter().write(mapper.writeValueAsString(
                new SessionAccessResponse(context.accountId(), context.accessContext(), context.expiresAt(), grants)));
    }
}
