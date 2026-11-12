package perf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe accounting for one stage. The clock is injected (milliseconds since the stage began) so
 * queue-delay semantics are testable without sleeping. Starts are recorded by the WORKER when it begins,
 * never at submission, so executor queueing is visible as start delay.
 */
final class StartLedger {
    /** A start more than this late is "late" (generator could not keep the scheduled cadence). */
    static final long LATE_MS = 250;

    static final class Kind {
        final AtomicInteger offered = new AtomicInteger(), submitted = new AtomicInteger(), started = new AtomicInteger(),
                completed = new AtomicInteger(), failed = new AtomicInteger(), missed = new AtomicInteger(),
                late = new AtomicInteger(), inflight = new AtomicInteger(), inflightPeak = new AtomicInteger();

        StartAccounting.Counts snapshot() {
            int done = completed.get() + failed.get();
            return new StartAccounting.Counts(offered.get(), submitted.get(), started.get(), completed.get(), failed.get(),
                    Math.max(0, submitted.get() - done), missed.get(), late.get(), inflightPeak.get());
        }
    }

    private final LongSupplier clockMillis;
    private final Kind journeys = new Kind(), polls = new Kind();
    private final AtomicInteger httpCalls = new AtomicInteger(), browserCalls = new AtomicInteger(),
            browserApi = new AtomicInteger(), browserPolls = new AtomicInteger();
    private final Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
    private final AtomicLong maxDelay = new AtomicLong();

    StartLedger(LongSupplier clockMillis) { this.clockMillis = clockMillis; }

    private Kind kind(boolean poll) { return poll ? polls : journeys; }

    void offered(boolean poll) { kind(poll).offered.incrementAndGet(); }
    void submitted(boolean poll) { kind(poll).submitted.incrementAndGet(); }
    void missed(boolean poll) { kind(poll).missed.incrementAndGet(); }

    /** Called from inside the worker thread. Returns the queue delay (actual start minus scheduled offset). */
    long started(boolean poll, long scheduledOffsetMillis) {
        Kind k = kind(poll);
        long delay = Math.max(0, clockMillis.getAsLong() - scheduledOffsetMillis);
        k.started.incrementAndGet();
        if (delay > LATE_MS) k.late.incrementAndGet();
        maxDelay.accumulateAndGet(delay, Math::max);
        int now = k.inflight.incrementAndGet();
        k.inflightPeak.accumulateAndGet(now, Math::max);
        return delay;
    }

    /** Called from inside the worker when it finishes (always, including failures). */
    void finished(boolean poll, boolean ok) {
        Kind k = kind(poll);
        k.inflight.decrementAndGet();
        (ok ? k.completed : k.failed).incrementAndGet();
    }

    void httpCall(int status) {
        httpCalls.incrementAndGet();
        statuses.computeIfAbsent(status, s -> new AtomicInteger()).incrementAndGet();
    }

    void browserCall(int apiCalls, int polls) {
        browserCalls.incrementAndGet();
        browserApi.addAndGet(apiCalls);
        browserPolls.addAndGet(polls);
    }

    StartAccounting snapshot() {
        Map<Integer, Integer> st = new java.util.TreeMap<>();
        statuses.forEach((k, v) -> st.put(k, v.get()));
        return new StartAccounting(journeys.snapshot(), polls.snapshot(), httpCalls.get(), st, browserCalls.get(), maxDelay.get(), browserApi.get(), browserPolls.get());
    }

    /**
     * Offered demand was not delivered: anything missed, anything never started, or too many late starts.
     * Such a stage is generator-saturated and cannot be capacity evidence.
     */
    boolean saturated() {
        for (Kind k : new Kind[]{journeys, polls}) {
            int offered = k.offered.get();
            if (offered == 0) continue;
            if (k.missed.get() > 0 || k.started.get() < offered) return true;
            if (k.late.get() > Math.max(1, offered / 20)) return true;
        }
        return false;
    }
}
