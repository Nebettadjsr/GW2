package application;

import api.ApiCompatibilityChecker;
import api.ApiCompatibilityResult;
import api.PublicApiTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A failed latest attempt must never leave an older success looking like the current result. */
class ApiCompatibilityServiceTest {

    /** Runs admitted work inline so the test needs no threads. */
    static final class InlineTasks implements BackgroundTasks {
        String lastOperation;
        @Override public String submit(String operation, Work work) { throw new UnsupportedOperationException(); }
        @Override public String submitAdministrative(String operation, Work work) {
            lastOperation = operation;
            try { work.run(); } catch (Exception ignored) { /* the facility records failures; the service already published state */ }
            return "t1";
        }
        @Override public Optional<Status> find(String taskId) { return Optional.empty(); }
    }

    private static ApiCompatibilityResult okResult() {
        var probe = new ApiCompatibilityResult.Probe("item", "Item", "/v2/items/{id}", "default", ApiCompatibilityResult.Status.OK, "COMPATIBLE", "ok");
        return new ApiCompatibilityResult(ApiCompatibilityResult.State.COMPLETE, Instant.parse("2026-11-19T10:00:00Z"), 5,
                ApiCompatibilityResult.Status.OK, "legacy", java.util.List.of(), java.util.List.of(), java.util.List.of(probe));
    }

    @Test
    void failingSecondRunReplacesTheEarlierSuccessWithASanitizedUnsuccessfulAttempt() {
        var calls = new AtomicInteger();
        var clock = Clock.fixed(Instant.parse("2026-11-19T11:00:00Z"), ZoneOffset.UTC);
        var tasks = new InlineTasks();
        PublicApiTransport transport = url -> new PublicApiTransport.Response(200, "{}");
        var service = new ApiCompatibilityService(tasks, () -> {
            if (calls.incrementAndGet() == 1) {
                return new ApiCompatibilityChecker(transport, clock, System::nanoTime) {
                    @Override public ApiCompatibilityResult check() { return okResult(); }
                };
            }
            throw new IllegalStateException("SECRET-INTERNAL-DETAIL");
        }, clock);

        service.start();
        assertEquals(ApiCompatibilityResult.Status.OK, service.read().result().overall());

        service.start(); // second attempt throws inside the checker supplier
        var view = service.read();
        assertEquals(ApiCompatibilityService.OPERATION, tasks.lastOperation);
        assertEquals(ApiCompatibilityResult.State.COMPLETE, view.result().state());
        assertEquals(ApiCompatibilityResult.Status.UNAVAILABLE, view.result().overall());
        assertEquals("CHECK_FAILED", view.result().probes().get(0).code());
        assertEquals(Instant.parse("2026-11-19T11:00:00Z"), view.result().checkedAt());
        assertFalse(view.result().toString().contains("SECRET-INTERNAL-DETAIL"));
        // The failed attempt released admission: a later check can start.
        assertDoesNotThrow(service::start);
    }

    @Test
    void secondAdmissionWhileRunningIsRefused() {
        BackgroundTasks never = new BackgroundTasks() {
            @Override public String submit(String o, Work w) { throw new UnsupportedOperationException(); }
            @Override public String submitAdministrative(String o, Work w) { return "t9"; } // never runs the work
            @Override public Optional<Status> find(String id) { return Optional.empty(); }
        };
        var service = new ApiCompatibilityService(never, () -> null, Clock.systemUTC());
        service.start();
        assertEquals(ApiCompatibilityResult.State.RUNNING, service.read().result().state());
        assertThrows(BackgroundTasks.OperationAlreadyRunning.class, service::start);
    }

    @Test
    void assertionErrorFromCheckerOrSupplierFailsTheRealTaskAndReleasesAdmission() {
        var clock = Clock.fixed(Instant.parse("2026-11-19T11:00:00Z"), ZoneOffset.UTC);
        PublicApiTransport transport = url -> new PublicApiTransport.Response(200, "{}");
        var calls = new AtomicInteger();
        try (var facility = new web.task.BackgroundTaskService(Runnable::run)) {
            var ids = new java.util.ArrayList<String>();
            var real = new web.BackgroundTaskServiceTasks(facility);
            BackgroundTasks tasks = new BackgroundTasks() {
                @Override public String submit(String o, Work w) { throw new UnsupportedOperationException(); }
                @Override public String submitAdministrative(String o, Work w) {
                    String id = real.submitAdministrative(o, w);
                    ids.add(id);
                    return id;
                }
                @Override public Optional<Status> find(String id) { return real.find(id); }
            };
            var service = new ApiCompatibilityService(tasks, () -> {
                int n = calls.incrementAndGet();
                if (n == 1) throw new AssertionError("SECRET-SUPPLIER-DETAIL");
                if (n == 2) return new ApiCompatibilityChecker(transport, clock, System::nanoTime) {
                    @Override public ApiCompatibilityResult check() { throw new AssertionError("SECRET-CHECKER-DETAIL"); }
                };
                return new ApiCompatibilityChecker(transport, clock, System::nanoTime) {
                    @Override public ApiCompatibilityResult check() { return okResult(); }
                };
            }, clock);

            for (int attempt = 0; attempt < 2; attempt++) {
                service.start();
                var view = service.read();
                assertEquals(ApiCompatibilityResult.State.COMPLETE, view.result().state());
                assertEquals(ApiCompatibilityResult.Status.UNAVAILABLE, view.result().overall());
                assertEquals("CHECK_FAILED", view.result().probes().get(0).code());
                assertFalse(view.result().toString().contains("SECRET"));
            }
            // Both tasks reached a terminal FAILED state in the real facility.
            for (String id : ids) {
                var status = tasks.find(id).orElseThrow();
                assertEquals("FAILED", status.state());
                assertTrue(status.terminal());
            }
            assertEquals(2, ids.size());
            // Operation key released: a later check is accepted and succeeds.
            assertDoesNotThrow(service::start);
            assertEquals(ApiCompatibilityResult.Status.OK, service.read().result().overall());
        }
    }
}
