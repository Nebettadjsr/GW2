package application;

import model.AccountScope;
import repo.AccountOverviewRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Own-account saved-data overview. The caller passes only sources already authorized from the live
 * proof (grants and URL restrictions); this service never widens that set. A failed query is
 * UNAVAILABLE, never zero; a source without a published marker is NOT_LOADED.
 */
public class AccountOverviewService {
    public static final String CURRENT = "CURRENT";
    public static final String STALE = "STALE";
    public static final String NOT_LOADED = "NOT_LOADED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    @FunctionalInterface
    public interface ConnectionSource { Connection open() throws SQLException; }

    /** Counts are null unless the state is CURRENT or STALE. {@code total} is materials only. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record SourceOverview(String state, Long count, Long total, Instant completedAt) {}

    private final ConnectionSource source;
    private final AccountOverviewRepository repository;
    private final Clock clock;
    private final Duration maxAge;

    public AccountOverviewService(ConnectionSource source, AccountOverviewRepository repository, Clock clock,
                                  Duration maxAge) {
        this.source = source;
        this.repository = repository;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    public Map<String, SourceOverview> read(AccountScope scope, Set<String> authorizedSources) {
        Map<String, SourceOverview> out = new LinkedHashMap<>();
        if (authorizedSources.isEmpty()) return out;
        Map<String, AccountOverviewRepository.Counts> counts;
        try (Connection con = source.open()) {
            counts = repository.read(con, scope, authorizedSources);
        } catch (SQLException | RuntimeException e) {
            for (String s : authorizedSources) out.put(s, new SourceOverview(UNAVAILABLE, null, null, null));
            return out;
        }
        Instant now = clock.instant();
        for (String s : authorizedSources) {
            var c = counts == null ? null : counts.get(s);
            if (c == null) {
                out.put(s, new SourceOverview(NOT_LOADED, null, null, null));
            } else {
                Instant at = c.state().completedAt();
                boolean stale = at == null || Duration.between(at, now).compareTo(maxAge) > 0;
                out.put(s, new SourceOverview(stale ? STALE : CURRENT, c.count(), c.total(), at));
            }
        }
        return out;
    }
}
