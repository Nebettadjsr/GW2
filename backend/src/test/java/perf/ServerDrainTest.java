package perf;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerDrainTest {
    @Test void counterOnlyUnitCheckFailsFirstDrainThenSucceedsOnSecond() {
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

    /** Real loopback HTTP: the client times out while the server request is still in flight (same counting pattern as the servlet filter). */
    @Test void realClientTimeoutWithServerRequestInFlightFailsFirstDrainThenSucceedsAfterLatchRelease() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/slow", ex -> {
            inFlight.incrementAndGet();
            try {
                entered.countDown();
                try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                byte[] b = "ok".getBytes();
                try { ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); } catch (java.io.IOException ignored) { /* client gone */ }
            } finally { ex.close(); inFlight.decrementAndGet(); }
        });
        server.start();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"))
                    .timeout(java.time.Duration.ofMillis(200)).build();
            assertThrows(java.net.http.HttpTimeoutException.class, () -> client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(ServerDrain.await(150, () -> inFlight.get() == 0, System::nanoTime, () -> sleep(10)), "client timed out but the server request is still in flight");
            release.countDown();
            assertTrue(ServerDrain.await(5000, () -> inFlight.get() == 0, System::nanoTime, () -> sleep(10)), "second drain succeeds after the latch releases");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
