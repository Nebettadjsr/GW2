package model;

import java.time.Instant;

/** Account-specific consumed Luck, identified by the stable GW2 account ID. */
public record AccountLuck(String accountId, long consumedLuck, Instant fetchedAt) {
    public AccountLuck {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId is required");
        if (consumedLuck < 0) throw new IllegalArgumentException("consumedLuck must be nonnegative");
        if (fetchedAt == null) throw new IllegalArgumentException("fetchedAt is required");
    }
}
