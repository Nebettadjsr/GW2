package repo;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Run in a fresh JVM with -Djdk.virtualThreadScheduler.parallelism=1. */
class DbPoolStartupIT {
    @Test void initializesOnTheOnlyVirtualThreadCarrier() throws Exception {
        Class.forName("org.postgresql.Driver");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var query = executor.submit(() -> {
                try (var connection = Db.open(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT 1")) {
                    assertTrue(rows.next());
                    return rows.getInt(1);
                }
            });
            assertEquals(1, query.get(10, TimeUnit.SECONDS));
        } finally { Db.closePool(); }
    }
}
