package application.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Cached total database size (STORY-OPS-001 section 8): a successful measurement is reused for five
 * minutes and concurrent refreshes coalesce into one in-flight query.
 */
public final class DatabaseSizeService {
    public static final Duration CACHE = Duration.ofMinutes(5);

    /** Narrow port: {@code SELECT pg_database_size(current_database())}. */
    public interface Measurement {
        long totalBytes() throws Exception;
    }

    private final Measurement measurement;
    private final Clock clock;
    private DatabaseSize lastSuccess;
    private CompletableFuture<DatabaseSize> inFlight;

    public DatabaseSizeService(Measurement measurement, Clock clock) {
        this.measurement = measurement;
        this.clock = clock;
    }

    public DatabaseSize current() {
        CompletableFuture<DatabaseSize> mine = null;
        CompletableFuture<DatabaseSize> join;
        synchronized (this) {
            if (lastSuccess != null && clock.instant().isBefore(lastSuccess.measuredAt().plus(CACHE))) {
                return lastSuccess;
            }
            if (inFlight == null) {
                inFlight = mine = new CompletableFuture<>();
            }
            join = inFlight;
        }
        if (mine != null) {
            DatabaseSize result;
            try {
                Instant at = clock.instant();
                long bytes = measurement.totalBytes();
                if (bytes < 0) throw new IllegalStateException("negative size");
                result = new DatabaseSize(DatabaseSize.State.AVAILABLE, bytes, at);
                synchronized (this) { lastSuccess = result; }
            } catch (Exception | LinkageError failure) {
                synchronized (this) {
                    result = lastSuccess == null ? new DatabaseSize(DatabaseSize.State.UNAVAILABLE, null, null)
                            : new DatabaseSize(DatabaseSize.State.STALE, lastSuccess.bytes(), lastSuccess.measuredAt());
                }
            } finally {
                synchronized (this) { inFlight = null; }
            }
            mine.complete(result);
            return result;
        }
        return join.join();
    }
}
