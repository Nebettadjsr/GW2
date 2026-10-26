package repo;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Investigation #116: run with one virtual-thread carrier and the experimental pool enabled. */
class DbPoolStartupIT {
    @Test void initializesPoolFromVirtualThreadWithoutPinningTheOnlyCarrier() throws Exception {
        assertTrue(Integer.getInteger("gw2.investigation.pool-size", 0) > 0);
        // Keep driver class loading out of the observed pool initialization, as Flyway does normally.
        Class.forName("org.postgresql.Driver");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> {
                try (var connection = Db.open(); var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT 1")) {
                    assertTrue(rows.next());
                    return rows.getInt(1);
                }
            });
            assertEquals(1, result.get(10, TimeUnit.SECONDS));
        }
    }
}
