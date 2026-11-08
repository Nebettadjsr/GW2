package web.access;

import application.access.AccountAccessService;
import application.access.OwnerPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import web.dto.ApiErrorResponse;

/**
 * HTTP-boundary owner check (STORY-STATUS-001). Reuses the account session/context rules: a missing
 * or expired session is 401; a mismatched context or an authenticated non-owner is 403. Applied by
 * the external administrative routes only, never inside shared application services, so scheduled
 * maintenance and ordinary on-demand work are unaffected. Independent of the legacy
 * {@code account-access.enforce} switch.
 */
public final class OwnerAccess {

    private final AccountAccessService access;
    private final OwnerPolicy policy;

    public OwnerAccess(AccountAccessService access, OwnerPolicy policy) {
        this.access = access;
        this.policy = policy;
    }

    /** @return {@code null} when the request is the current owner's; otherwise the denial to send */
    public ResponseEntity<ApiErrorResponse> deny(HttpServletRequest request) {
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty() || access == null) return denied(HttpStatus.UNAUTHORIZED);
        var resolution = access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> { return denied(HttpStatus.UNAUTHORIZED); }
            case CONTEXT_MISMATCH -> { return denied(HttpStatus.FORBIDDEN); }
            case AUTHENTICATED -> { /* owner check below */ }
        }
        return policy != null && policy.isOwner(resolution.context()) ? null : denied(HttpStatus.FORBIDDEN);
    }

    private static ResponseEntity<ApiErrorResponse> denied(HttpStatus status) {
        boolean unauthenticated = status == HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(unauthenticated ? "SESSION_REQUIRED" : "OWNER_REQUIRED",
                        unauthenticated ? "Prove a GW2 API key to continue"
                                : "This operation is restricted to the system owner"));
    }
}
