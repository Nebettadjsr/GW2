package perf;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerDrainTest {
    @Test void timedOutClientWithStillRunningServerWorkFailsFirstDrainThenSucceedsOnSecond() {
        AtomicInteger serverInFlight = new AtomicInteger(1); // the client timed out but Tomcat/JDBC work continues
        AtomicLong clock = new AtomicLong();
        Runnable pause = () -> clock.addAndGet(20_000_000L);
        assertFalse(ServerDrain.await(100, () -> serverInFlight.get() == 0, clock::get, pause), "server work still running: not drained");
        serverInFlight.set(0); // the server finally completes
        assertTrue(ServerDrain.await(100, () -> serverInFlight.get() == 0, clock::get, pause), "second drain succeeds");
    }

    @Test void idleServerDrainsImmediately() {
        assertTrue(ServerDrain.await(1, () -> true, System::nanoTime, () -> fail("must not pause")));
    }

    @Test void resourceWindowReportsUnitsAndTreatsUnavailableRssAsLegitimate() {
        String w = new ResourceWindow("coarse:2:seed7", 20_000, 10_000_000_000L, 4, 512L << 20, 2048L << 20, -1).describe();
        assertTrue(w.contains("0.50 core-equivalents of 4 logical cores"), w);
        assertTrue(w.contains("heap peak 512 MiB") && w.contains("limit 2048 MiB") && w.contains("headroom 1536 MiB"), w);
        assertTrue(w.contains("resident memory unavailable on this OS"), w);
        assertFalse(w.contains("cpu unavailable") || w.contains("heap unavailable"), "other resources are still reported: " + w);
    }
}
