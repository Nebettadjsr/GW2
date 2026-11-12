package perf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StartLedgerTest {
    @Test void queueDelayIsMeasuredWhenTheWorkerStartsNotAtSubmission() {
        AtomicLong clock = new AtomicLong();
        StartLedger l = new StartLedger(clock::get);
        l.offered(false);
        clock.set(1_000);            // submitted on time at its scheduled offset...
        l.submitted(false);
        clock.set(1_900);            // ...but the worker only began 900 ms later
        long delay = l.started(false, 1_000);
        l.finished(false, true);
        assertEquals(900, delay);
        StartAccounting a = l.snapshot();
        assertEquals(900, a.maxQueueDelayMillis());
        assertEquals(1, a.journeys().late());
        assertEquals(1, a.journeys().completed());
        assertEquals(0, a.journeys().pending());
    }

    @Test void startsPollsAndHttpCallsAreCountedSeparately() {
        AtomicLong clock = new AtomicLong();
        StartLedger l = new StartLedger(clock::get);
        for (boolean poll : new boolean[]{false, true, true}) { l.offered(poll); l.submitted(poll); l.started(poll, 0); l.finished(poll, true); }
        l.httpCall(200); l.httpCall(200); l.httpCall(503);
        for (int i = 0; i < 8; i++) l.probeCall();
        l.browserWindow(3, 1);
        StartAccounting a = l.snapshot();
        assertEquals(1, a.journeys().started());
        assertEquals(2, a.polls().started());
        assertEquals(3, a.httpCalls());
        assertEquals(2, a.httpStatuses().get(200));
        assertEquals(1, a.httpStatuses().get(503));
        assertEquals(8, a.browserProbeCalls(), "actual probe calls, not window reads");
        assertEquals(3, a.browserApiCalls());
        assertEquals(1, a.browserPolls());
        assertFalse(l.saturated());
    }

    @Test void undeliveredOfferedDemandSaturatesTheGenerator() {
        StartLedger missed = new StartLedger(() -> 0);
        missed.offered(false); missed.missed(false);
        assertTrue(missed.saturated());

        StartLedger neverStarted = new StartLedger(() -> 0);
        neverStarted.offered(false); neverStarted.submitted(false);
        assertTrue(neverStarted.saturated());
        assertEquals(1, neverStarted.snapshot().journeys().pending());

        AtomicLong clock = new AtomicLong();
        StartLedger late = new StartLedger(clock::get);
        for (int i = 0; i < 10; i++) { late.offered(false); late.submitted(false); clock.set(10_000); late.started(false, 0); late.finished(false, true); }
        assertTrue(late.saturated(), "most starts were late: offered cadence was not delivered");
    }

    @Test void inflightPeakIsTracked() {
        StartLedger l = new StartLedger(() -> 0);
        for (int i = 0; i < 3; i++) { l.offered(false); l.submitted(false); l.started(false, 0); }
        l.finished(false, true);
        assertEquals(3, l.snapshot().journeys().inflightPeak());
        assertEquals(2, l.snapshot().journeys().pending());
    }

    @Test void retainedWorkersAreClearedOnlyAfterTheyActuallyFinish() {
        RetainedWorkers r = new RetainedWorkers();
        CompletableFuture<Void> running = new CompletableFuture<>();
        r.retain(List.of(running));
        assertTrue(r.any(), "a still-running worker keeps the resources retained");
        running.complete(null);
        assertFalse(r.any(), "after a successful drain nothing is retained, so cleanupRequired clears");
        assertTrue(r.snapshot().isEmpty());
    }
}
