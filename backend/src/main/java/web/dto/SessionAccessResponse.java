package web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Transport response body for {@code POST /api/session} and {@code POST /api/session/renew}
 * (STORY-API-010, TARGET_ARCHITECTURE.md §8.1 and §8.2).
 *
 * <p>Carries nothing secret. The session identifier stays in the HttpOnly cookie and the GW2 key is
 * not echoed. The backend-verified account GUID is exposed only as a browser preference namespace
 * (STORY-MAT-004); it is neither authentication nor authorization evidence.
 *
 * @param accountId     the backend-verified stable account GUID, never derived from the raw key
 * @param accessContext the non-secret identifier bound to this exact session, to be sent back in
 *                      {@code X-Account-Context}; a consistency assertion, never a credential
 * @param expiresAt     the session's absolute expiry, so the browser can renew rather than guess
 * @param grants        the effective grants of the key that proved this session
 */
public record SessionAccessResponse(String accountId, String accessContext, Instant expiresAt, List<String> grants) {}
