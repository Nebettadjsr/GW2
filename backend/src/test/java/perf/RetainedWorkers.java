package perf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/** Futures that did not finish within a bound. Cleared once they have actually completed. */
final class RetainedWorkers {
    private final List<Future<?>> retained = new CopyOnWriteArrayList<>();

    void retain(Iterable<? extends Future<?>> futures) {
        for (Future<?> f : futures) if (!f.isDone()) retained.add(f);
    }

    /** Drops completed futures; returns true when nothing is still running. */
    boolean reconcile() {
        retained.removeIf(Future::isDone);
        return retained.isEmpty();
    }

    boolean any() { return !reconcile(); }

    List<Future<?>> snapshot() { return List.copyOf(retained); }
}
