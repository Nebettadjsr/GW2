package web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Transport response body for {@code POST /api/session} and {@code POST /api/session/renew}
 * (STORY-API-010, TARGET_ARCHITECTURE.md §8.1 and §8.2).
 *
 * <p>Carries nothing secret and nothing identifying. The session identifier stays in the HttpOnly
 * cookie, the GW2 key is not echoed, and the account GUID is deliberately absent: the browser needs
 * to know which access context it holds and how long, not which account GUID the backend resolved.
 *
 * @param accessContext the non-secret identifier bound to this exact session, to be sent back in
 *                      {@code X-Account-Context}; a consistency assertion, never a credential
 * @param expiresAt     the session's absolute expiry, so the browser can renew rather than guess
 * @param grants        the effective grants of the key that proved this session
 */
public record SessionAccessResponse(String accessContext, Instant expiresAt, List<String> grants) {}
