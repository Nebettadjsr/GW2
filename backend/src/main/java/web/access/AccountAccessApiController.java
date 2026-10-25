package web.access;

import application.access.AccountAccessService;
import application.access.AccountContext;
import application.access.AccountGrant;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import application.access.ProofUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.ApiErrorResponse;
import web.dto.SessionAccessResponse;

import java.util.Comparator;
import java.util.List;

/**
 * The session proof, renewal and logout HTTP contract (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
 *
 * <p>All three are state-changing and all three validate the origin first - including proof, which
 * is the one an attacker would otherwise use to install their own account in a victim's browser.
 * Every response is {@code no-store}: one of them carries a session identifier, and the rest carry
 * the reason a credential was refused.
 *
 * <p>The GW2 key is read from {@link AccountAccessHttp#API_KEY_HEADER} into a local variable, handed
 * to the use case, and never stored, logged or echoed. Responses are built from
 * {@link SessionAccessResponse}, which has no field able to carry one.
 */
@RestController
@RequestMapping("/api/session")
public class AccountAccessApiController {

    private final AccountAccessService access;
    private final AccountAccessHttp http;

    public AccountAccessApiController(AccountAccessService access, AccountAccessHttp http) {
        this.access = access;
        this.http = http;
    }

    /** Establishes access from a transient key: two upstream validations, then a fresh session. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> prove(HttpServletRequest request) {
        if (!http.isSameOrigin(request)) return originRejected();
        String apiKey = AccountAccessHttp.presentedApiKey(request);
        if (apiKey == null) return credentialRequired();
        try {
            return issued(request, access.establish(apiKey), HttpStatus.OK);
        } catch (InvalidCredentialException | MalformedProofException e) {
            return credentialRejected();
        } catch (ProofUnavailableException e) {
            return proofUnavailable(e);
        }
    }

    /**
     * Repeats upstream validation with a presented key and issues a new session.
     *
     * <p>Requires the current session and its access context as well as the key: renewal is only
     * meaningful for the tab that holds the lease being replaced, and a stale tab must not be able to
     * move the shared cookie to a session it never proved.
     */
    @PostMapping(path = "/renew", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> renew(HttpServletRequest request) {
        if (!http.isSameOrigin(request)) return originRejected();
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty()) return sessionRequired();
        String apiKey = AccountAccessHttp.presentedApiKey(request);
        if (apiKey == null) return credentialRequired();
        AccountSessions.Resolution resolution =
                access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> { return sessionRequired(); }
            case CONTEXT_MISMATCH -> { return contextRejected(); }
            case AUTHENTICATED -> { /* fall through to renewal */ }
        }
        try {
            return issued(request, access.renew(presented.get(),
                    AccountAccessHttp.assertedAccessContext(request), apiKey), HttpStatus.OK);
        } catch (InvalidCredentialException | MalformedProofException e) {
            return credentialRejected();
        } catch (ProofUnavailableException e) {
            return proofUnavailable(e);
        } catch (AccountAccessService.AccountMismatchException e) {
            return accountRejected();
        }
    }

    /**
     * Revokes the presented session server-side.
     *
     * <p>The access context must match the session being revoked, so a stale tab sharing the cookie
     * scope cannot log out the session a newer tab established.
     */
    @PostMapping(path = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> logout(HttpServletRequest request) {
        if (!http.isSameOrigin(request)) return originRejected();
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty()) return sessionRequired();
        AccountSessions.Resolution resolution =
                access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        return switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> sessionRequired();
            case CONTEXT_MISMATCH -> contextRejected();
            case AUTHENTICATED -> {
                access.logout(presented.get());
                yield noStore(HttpStatus.NO_CONTENT)
                        .header(HttpHeaders.SET_COOKIE, http.clearedSessionCookie(request))
                        .build();
            }
        };
    }

    private ResponseEntity<?> issued(HttpServletRequest request, AccountSessions.Issued session,
                                     HttpStatus status) {
        AccountContext context = session.context();
        return noStore(status)
                .header(HttpHeaders.SET_COOKIE, http.sessionCookie(request, session.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext())
                .body(new SessionAccessResponse(context.accessContext(), context.expiresAt(),
                        grantNames(context)));
    }

    private static List<String> grantNames(AccountContext context) {
        return context.grants().stream().map(AccountGrant::apiName).sorted(Comparator.naturalOrder())
                .toList();
    }

    /** Every session response, successful or not, is uncacheable: §8.1 requires {@code no-store}. */
    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache");
    }

    private static ResponseEntity<?> originRejected() {
        return error(HttpStatus.FORBIDDEN, "ORIGIN_REJECTED",
                "This request did not come from this application's own origin");
    }

    private static ResponseEntity<?> credentialRequired() {
        return error(HttpStatus.BAD_REQUEST, "CREDENTIAL_REQUIRED",
                "Supply a GW2 API key in the " + AccountAccessHttp.API_KEY_HEADER + " header");
    }

    /** One answer for rejected and unusable credentials alike: neither establishes anything. */
    private static ResponseEntity<?> credentialRejected() {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIAL",
                "The GW2 API key could not be validated");
    }

    private static ResponseEntity<?> sessionRequired() {
        return error(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Prove a GW2 API key to continue");
    }

    private static ResponseEntity<?> contextRejected() {
        return error(HttpStatus.FORBIDDEN, "ACCESS_CONTEXT_MISMATCH",
                "This request's access context does not belong to the authenticated session");
    }

    private static ResponseEntity<?> accountRejected() {
        return error(HttpStatus.FORBIDDEN, "ACCOUNT_MISMATCH",
                "The supplied credential does not match the authenticated session");
    }

    private static ResponseEntity<?> proofUnavailable(ProofUnavailableException e) {
        return noStore(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter().toSeconds()))
                .body(new ApiErrorResponse("PROOF_UNAVAILABLE",
                        "GW2 could not validate the key right now; retry in "
                                + e.retryAfter().toSeconds() + " seconds"));
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return noStore(status).body(new ApiErrorResponse(code, message));
    }
}
