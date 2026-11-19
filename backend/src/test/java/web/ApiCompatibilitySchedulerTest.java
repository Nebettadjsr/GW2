package web;

import application.ApiCompatibilityService;
import application.BackgroundTasks;
import api.ApiCompatibilityChecker;
import api.ApiCompatibilityResult;
import api.PublicApiTransport;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Offline: fake task facility and checker; no ArenaNet request is possible. */
class ApiCompatibilitySchedulerTest {

    /** Holds admitted work until the test completes it, like a busy executor. */
    static final class HeldTasks implements BackgroundTasks {
        final List<Work> held = new ArrayList<>();
        @Override public String submit(String operation, Work work) { throw new UnsupportedOperationException(); }
        @Override public String submitAdministrative(String operation, Work work) { held.add(work); return "t" + held.size(); }
        @Override public Optional<Status> find(String taskId) { return Optional.empty(); }
    }

    @Test
    void scheduledRunAdmitsOneCheckAndADuplicateWhileRunningIsSafelyIgnored() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-11-19T11:00:00Z"), ZoneOffset.UTC);
        var tasks = new HeldTasks();
        var runs = new AtomicInteger();
        PublicApiTransport never = url -> { throw new AssertionError("no upstream call expected"); };
        var service = new ApiCompatibilityService(tasks, () -> new ApiCompatibilityChecker(never, clock, System::nanoTime) {
            @Override public ApiCompatibilityResult check() { runs.incrementAndGet(); return ApiCompatibilityResult.failedAttempt(clock.instant()); }
        }, clock);
        var scheduler = new ApiCompatibilityScheduler(service);

        assertEquals(ApiCompatibilityResult.State.NOT_CHECKED, service.read().result().state());
        scheduler.checkApiCompatibility();
        scheduler.checkApiCompatibility(); // duplicate while the first is in flight
        assertEquals(1, tasks.held.size());
        assertEquals(ApiCompatibilityResult.State.RUNNING, service.read().result().state());

        tasks.held.get(0).run();
        assertEquals(1, runs.get());
        assertEquals(ApiCompatibilityResult.State.COMPLETE, service.read().result().state());

        scheduler.checkApiCompatibility(); // a later scheduled run is admitted again
        assertEquals(2, tasks.held.size());
    }

    @Test
    void schedulerSwallowsAdmissionFailureAndIsConfigurableWithSixHourOneMinuteDefaults() throws Exception {
        BackgroundTasks refusing = new BackgroundTasks() {
            @Override public String submit(String o, Work w) { throw new UnsupportedOperationException(); }
            @Override public String submitAdministrative(String o, Work w) { throw new IllegalStateException("full"); }
            @Override public Optional<Status> find(String id) { return Optional.empty(); }
        };
        var service = new ApiCompatibilityService(refusing, () -> null, Clock.systemUTC());
        assertDoesNotThrow(() -> new ApiCompatibilityScheduler(service).checkApiCompatibility());
        assertEquals(ApiCompatibilityResult.State.NOT_CHECKED, service.read().result().state());

        var s = ApiCompatibilityScheduler.class.getMethod("checkApiCompatibility").getAnnotation(Scheduled.class);
        assertEquals("${gw2.api-compatibility.interval-ms:21600000}", s.fixedDelayString());
        assertEquals("${gw2.api-compatibility.initial-delay-ms:60000}", s.initialDelayString());
    }
}
