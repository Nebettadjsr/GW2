package web.dto;

import java.util.List;

/**
 * Freshness of the account inputs a Discovery response used (STORY-API-011). Absent on a response
 * whose inputs are all current and need nothing.
 *
 * @param status  PENDING (an input was never published; no result), or STALE (usable but expired)
 * @param sources the missing (PENDING) or expired (STALE) account sources
 * @param refresh CREDENTIAL_REQUIRED when a refresh is needed but no key was supplied (nothing was
 *                admitted); ADMITTED, QUEUED_BEHIND_ACTIVE_RUN, SUFFICIENT_SCOPE or
 *                WAITING_FOR_ACTIVE_TASK when a keyed request admitted (or was told to await) a refresh
 * @param taskId  the account refresh task to poll, when one was admitted or is active
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DiscoveryFreshnessDto(String status, List<String> sources, String refresh, String taskId) {
    public static final String PENDING = "PENDING";
    public static final String STALE = "STALE";
}
