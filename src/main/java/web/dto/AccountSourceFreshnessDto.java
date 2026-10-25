package web.dto;

import java.time.Instant;

/**
 * Freshness of the one account source an account read consumed (STORY-API-011).
 *
 * @param status      PENDING (never published, no data), CURRENT or STALE (usable but expired)
 * @param completedAt when the source last published successfully; null while PENDING
 * @param refresh     CREDENTIAL_REQUIRED when a refresh is needed but this keyless read cannot admit
 *                    one; null otherwise
 */
public record AccountSourceFreshnessDto(String status, Instant completedAt, String refresh) {
    public static AccountSourceFreshnessDto of(application.ScopedSourceGate.Read<?> read) {
        return new AccountSourceFreshnessDto(read.status().name(), read.completedAt(), read.refresh());
    }
}
