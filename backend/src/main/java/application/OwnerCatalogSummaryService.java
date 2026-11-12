package application;

import repo.SharedDataSummaryRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;

/**
 * Owner-only shared-data and account-usage aggregates. Each dataset is read independently; a failed
 * query yields an explicit UNAVAILABLE state, never zero, and never a fabricated timestamp. It makes no
 * upstream call and writes nothing. Callers must authorize before invoking it.
 */
public class OwnerCatalogSummaryService {
    @FunctionalInterface
    public interface ConnectionSource { Connection open() throws SQLException; }

    /** AVAILABLE rows carry the figures; UNAVAILABLE rows carry only the state. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Dataset(String state, Long storedRows, Long timestampedRows, Long unknownTimestampRows,
                          Instant oldestFetchedAt, Instant newestFetchedAt) {
        static Dataset of(SharedDataSummaryRepository.Coverage c) {
            return new Dataset("AVAILABLE", c.storedRows(), c.timestampedRows(), c.unknownTimestampRows(),
                    c.oldestFetchedAt(), c.newestFetchedAt());
        }
        static Dataset unavailable() { return new Dataset("UNAVAILABLE", null, null, null, null, null); }
    }

    public record Accounts(String state, Long totalAccounts, Long activeLast60Minutes) {}

    public record Summary(Dataset items, Dataset recipes, Dataset tradingPostPrices, Accounts accounts) {}

    private interface Query<T> { T run(Connection con) throws SQLException; }

    private final ConnectionSource source;
    private final SharedDataSummaryRepository repository;
    private final Clock clock;

    public OwnerCatalogSummaryService(ConnectionSource source, SharedDataSummaryRepository repository, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.clock = clock;
    }

    public Summary read() {
        Instant evaluatedAt = clock.instant();
        return new Summary(
                dataset(repository::items),
                dataset(repository::recipes),
                dataset(repository::tradingPostPrices),
                accounts(evaluatedAt));
    }

    private Dataset dataset(Query<SharedDataSummaryRepository.Coverage> q) {
        try (Connection con = source.open()) {
            return Dataset.of(q.run(con));
        } catch (SQLException | RuntimeException e) {
            return Dataset.unavailable();
        }
    }

    private Accounts accounts(Instant evaluatedAt) {
        try (Connection con = source.open()) {
            var usage = repository.accounts(con, evaluatedAt);
            return new Accounts("AVAILABLE", usage.totalAccounts(), usage.activeLast60Minutes());
        } catch (SQLException | RuntimeException e) {
            return new Accounts("UNAVAILABLE", null, null);
        }
    }
}
