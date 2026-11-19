package application;

import api.ApiCompatibilityChecker;
import api.ApiCompatibilityResult;
import web.task.BackgroundTaskService;
import web.task.TaskAlreadyRunningException;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Owner API compatibility check (STORY-OPS-008): admission through the bounded administrative task
 * executor with in-flight coalescing (a second request is rejected), and a single in-process latest
 * result. Reading is cache-only and never touches ArenaNet. After a restart the result is truthfully
 * "not checked"; nothing is persisted.
 */
public class ApiCompatibilityService {

    public static final String OPERATION = "API_COMPATIBILITY_CHECK";
    /** A completed result older than this is shown as stale, never as a current success. */
    public static final Duration STALE_AFTER = Duration.ofHours(24);

    /** What the owner sees: the cached result plus server-computed staleness. */
    public record View(ApiCompatibilityResult result, boolean stale) {}

    private final BackgroundTaskService tasks;
    private final Supplier<ApiCompatibilityChecker> checker;
    private final Clock clock;

    private ApiCompatibilityResult latest = ApiCompatibilityResult.notChecked();
    private boolean running;
    private String runningTaskId;

    public ApiCompatibilityService(BackgroundTaskService tasks, Supplier<ApiCompatibilityChecker> checker, Clock clock) {
        this.tasks = tasks;
        this.checker = checker;
        this.clock = clock;
    }

    /** Admits one check; throws {@link TaskAlreadyRunningException} while one is in flight. */
    public synchronized void start() {
        if (running) throw new TaskAlreadyRunningException(OPERATION, runningTaskId);
        running = true;
        try {
            runningTaskId = tasks.submitAdministrative(OPERATION, this::run);
        } catch (RuntimeException e) {
            running = false;
            throw e;
        }
    }

    private void run() {
        ApiCompatibilityResult result;
        try {
            result = checker.get().check();
        } catch (RuntimeException | Error e) {
            // A checker defect must not look like success or leak: keep the previous result.
            synchronized (this) { running = false; runningTaskId = null; }
            throw e;
        }
        synchronized (this) {
            latest = result;
            running = false;
            runningTaskId = null;
        }
    }

    /** Cache-only read. */
    public synchronized View read() {
        var r = latest;
        boolean stale = r.state() == ApiCompatibilityResult.State.COMPLETE && r.checkedAt() != null
                && Duration.between(r.checkedAt(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new View(running ? r.asRunning() : r, stale);
    }
}
