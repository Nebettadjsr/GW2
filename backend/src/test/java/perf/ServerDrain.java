package perf;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded wait for the server side (in-flight HTTP requests and background jobs) to drain; a timeout is retryable. */
final class ServerDrain {
    private ServerDrain() {}

    static boolean await(long timeoutMillis, Supplier<Boolean> drained, LongSupplier nanos, Runnable pause) {
        long deadline = nanos.getAsLong() + timeoutMillis * 1_000_000L;
        while (!drained.get()) {
            if (nanos.getAsLong() > deadline) return false;
            pause.run();
        }
        return true;
    }
}
